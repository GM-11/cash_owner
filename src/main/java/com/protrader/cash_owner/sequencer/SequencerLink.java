package com.protrader.cash_owner.sequencer;

import com.protrader.cash_owner.config.CashOwnerProperties;
import com.protrader.proto.exchange.v1.SequencerGrpc;
import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class SequencerLink implements SequencerPort, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SequencerLink.class);

    private final SequencerGrpc.SequencerStub stub;
    private final CashOwnerProperties.Sequencer cfg;
    private final ScheduledExecutorService timer = Executors
            .newSingleThreadScheduledExecutor(Thread.ofPlatform().name("sequencer-link").daemon().factory());

    private final List<Consumer<SubmitAck>> ackHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> openedHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> lostHandlers = new CopyOnWriteArrayList<>();

    private StreamObserver<SubmitRequest> out; // guarded by this; null = no stream that has opened (headers seen)
    private Attempt current; // guarded by this; the attempt in progress or open
    private boolean running; // guarded by this
    private long backoffMs; // guarded by this

    public SequencerLink(SequencerGrpc.SequencerStub stub, CashOwnerProperties props) {
        this.stub = stub;
        this.cfg = props.sequencer();
    }

    public void onAck(Consumer<SubmitAck> handler) {
        ackHandlers.add(handler);
    }

    public void onStreamOpened(Runnable handler) {
        openedHandlers.add(handler);
    }

    public void onStreamLost(Runnable handler) {
        lostHandlers.add(handler);
    }

    /** Sends one request. false = no open stream; nothing was sent. */
    public synchronized boolean submit(SubmitRequest request) {
        if (out == null) {
            return false;
        }
        try {
            out.onNext(request);
            return true;
        } catch (RuntimeException e) {
            return false; // the stream's onError will follow and trigger a reconnect
        }
    }

    public synchronized boolean isStreamOpen() {
        return out != null;
    }

    @Override
    public synchronized void start() {
        if (!cfg.enabled()) {
            log.info("sequencer link disabled (cash-owner.sequencer.enabled=false)");
            return;
        }
        running = true;
        backoffMs = cfg.reconnectMin().toMillis();
        timer.execute(this::connect);
    }

    @Override
    public void stop() {
        StreamObserver<SubmitRequest> toClose;
        synchronized (this) {
            running = false;
            toClose = current == null ? null : current.requests; // open, or still waiting for headers
            current = null;
            out = null;
        }
        if (toClose != null) {
            try {
                toClose.onCompleted();
            } catch (RuntimeException ignored) {
                // already broken
            }
        }
        timer.shutdownNow();
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    /** One connection attempt. All fields guarded by the link. */
    private static final class Attempt {
        StreamObserver<SubmitRequest> requests; // set once stub.submit(...) has returned
        boolean headersSeen;
        boolean open;
    }

    private void connect() {
        Attempt attempt = new Attempt();
        synchronized (this) {
            if (!running) {
                return;
            }
            current = attempt;
        }

        StreamObserver<SubmitAck> responses = new StreamObserver<>() {
            @Override
            public void onNext(SubmitAck ack) {
                synchronized (SequencerLink.this) {
                    backoffMs = cfg.reconnectMin().toMillis(); // the stream works: reset backoff
                }
                if (ackHandlers.isEmpty()) {
                    log.debug("sequencer ack (no handler yet): {}", ack);
                }
                for (Consumer<SubmitAck> handler : ackHandlers) {
                    handler.accept(ack);
                }
            }

            @Override
            public void onError(Throwable t) {
                closed(attempt, t.toString());
            }

            @Override
            public void onCompleted() {
                closed(attempt, "server closed the stream");
            }
        };
        // The call is not "open" until the server's headers arrive: a connect to a dead
        // or wrong address would
        // otherwise look open, and orders sent into it would end up in doubt.
        StreamObserver<SubmitRequest> pending = stub.withInterceptors(new HeadersInterceptor(attempt))
                .submit(responses);
        boolean opened;
        synchronized (this) {
            if (current != attempt) {
                return; // it already failed (closed() has scheduled the reconnect), or we were stopped
            }
            attempt.requests = pending;
            if (!running) {
                current = null;
                pending.onCompleted();
                return;
            }
            opened = tryOpen(attempt);
        }
        if (opened) {
            announceOpen();
        }
    }

    /** Headers arrived on this attempt's call (any thread). */
    private void headersArrived(Attempt attempt) {
        boolean opened;
        synchronized (this) {
            attempt.headersSeen = true;
            opened = tryOpen(attempt);
        }
        if (opened) {
            announceOpen();
        }
    }

    /**
     * Must hold the lock. True exactly once per attempt: this call made it the open
     * stream.
     */
    private boolean tryOpen(Attempt attempt) {
        if (current != attempt || !running || attempt.open || !attempt.headersSeen || attempt.requests == null) {
            return false;
        }
        attempt.open = true;
        out = attempt.requests;
        return true;
    }

    private void announceOpen() {
        log.info("sequencer stream started");
        openedHandlers.forEach(Runnable::run);
    }

    private void closed(Attempt attempt, String why) {
        long delay;
        boolean wasOpen;
        synchronized (this) {
            if (current != attempt) {
                return; // an old attempt, already replaced, or we were stopped
            }
            current = null;
            wasOpen = attempt.open;
            if (wasOpen) {
                out = null;
            }
            if (!running) {
                return;
            }
            delay = backoffMs;
            backoffMs = Math.min(backoffMs * 2, cfg.reconnectMax().toMillis());
        }
        if (wasOpen) {
            log.warn("sequencer stream lost ({}); reconnecting in {} ms", why, delay);
            lostHandlers.forEach(Runnable::run);
        } else {
            // never open, so nothing was lost: no onStreamLost
            log.warn("sequencer stream failed before it opened ({}); reconnecting in {} ms", why, delay);
        }
        timer.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /** Tells the link when the server's headers arrive for one specific call. */
    private final class HeadersInterceptor implements ClientInterceptor {
        private final Attempt attempt;

        HeadersInterceptor(Attempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                @Override
                public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(
                            responseListener) {
                        @Override
                        public void onHeaders(Metadata headers) {
                            headersArrived(attempt);
                            super.onHeaders(headers);
                        }
                    }, headers);
                }
            };
        }
    }
}
