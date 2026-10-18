package com.protrader.cash_owner.sequencer;

import com.protrader.cash_owner.config.CashOwnerProperties;
import com.protrader.proto.exchange.v1.SequencerGrpc;
import com.protrader.proto.exchange.v1.SubmitAck;
import com.protrader.proto.exchange.v1.SubmitRequest;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class SequencerLink implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SequencerLink.class);

    private final SequencerGrpc.SequencerStub stub;
    private final CashOwnerProperties.Sequencer cfg;
    private final ScheduledExecutorService timer = Executors
            .newSingleThreadScheduledExecutor(Thread.ofPlatform().name("sequencer-link").daemon().factory());

    private volatile Consumer<SubmitAck> onAck = ack -> log.debug("sequencer ack (no handler yet): {}", ack);
    private volatile Runnable onStreamOpened = () -> {
    };
    private volatile Runnable onStreamLost = () -> {
    };

    private StreamObserver<SubmitRequest> out; // guarded by this; null = stream down
    private boolean running; // guarded by this
    private long backoffMs; // guarded by this

    public SequencerLink(SequencerGrpc.SequencerStub stub, CashOwnerProperties props) {
        this.stub = stub;
        this.cfg = props.sequencer();
    }

    public void onAck(Consumer<SubmitAck> handler) {
        this.onAck = handler;
    }

    public void onStreamOpened(Runnable handler) {
        this.onStreamOpened = handler;
    }

    public void onStreamLost(Runnable handler) {
        this.onStreamLost = handler;
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
            toClose = out;
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

    private void connect() {
        synchronized (this) {
            if (!running) {
                return;
            }
        }

        final StreamObserver<SubmitRequest>[] mine = new StreamObserver[1];
        StreamObserver<SubmitAck> responses = new StreamObserver<>() {
            @Override
            public void onNext(SubmitAck ack) {
                synchronized (SequencerLink.this) {
                    backoffMs = cfg.reconnectMin().toMillis(); // the stream works: reset backoff
                }
                onAck.accept(ack);
            }

            @Override
            public void onError(Throwable t) {
                lost(mine[0], t.toString());
            }

            @Override
            public void onCompleted() {
                lost(mine[0], "server closed the stream");
            }
        };
        StreamObserver<SubmitRequest> requests = stub.submit(responses);
        synchronized (this) {
            mine[0] = requests;
            if (!running) {
                requests.onCompleted();
                return;
            }
            out = requests;
        }
        log.info("sequencer stream started");
        onStreamOpened.run();
    }

    private void lost(StreamObserver<SubmitRequest> stream, String why) {
        long delay;
        synchronized (this) {
            if (stream == null || out != stream) {
                return; // an old stream, already replaced
            }
            out = null;
            if (!running) {
                return;
            }
            delay = backoffMs;
            backoffMs = Math.min(backoffMs * 2, cfg.reconnectMax().toMillis());
        }
        log.warn("sequencer stream lost ({}); reconnecting in {} ms", why, delay);
        onStreamLost.run();
        timer.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }
}
