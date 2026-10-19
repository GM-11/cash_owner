package com.protrader.cash_owner.owner;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.protrader.cash_owner.config.CashOwnerProperties;
import com.protrader.cash_owner.services.Ledger;

@Component
public final class LedgerOwner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LedgerOwner.class);

    /**
     * Starts before and stops after the gRPC server, SequencerLink and the Kafka
     * listener.
     */
    private static final int PHASE = Integer.MIN_VALUE + 1000;

    public record Settings(int maxPendingRequests, boolean checkInvariants, Duration shutdownDrain) {
    }

    private enum State {
        NEW, RUNNING, STOPPING, STOPPED
    }

    private static final class Task<T> {
        final Function<Ledger, T> job;
        final CompletableFuture<T> future;
        final boolean isRequest;

        Task(Function<Ledger, T> job, CompletableFuture<T> future, boolean isRequest) {
            this.job = job;
            this.future = future;
            this.isRequest = isRequest;
        }
    }

    private final Ledger ledger = new Ledger();
    private final Settings settings;
    private final Consumer<Ledger> invariantCheck;

    private final LinkedBlockingQueue<Task<?>> queue = new LinkedBlockingQueue<>();
    private final Task<Void> stopMarker = new Task<>(null, null, false);
    private final AtomicInteger pendingRequests = new AtomicInteger();
    private final ExecutorService replyExecutor = newReplyExecutor();

    /**
     * Guards state/broken together with "enqueue", so nothing is queued after the
     * owner stops accepting.
     */
    private final Object lock = new Object();
    private volatile State state = State.NEW;
    private volatile boolean broken;
    private volatile boolean abort;
    private volatile Thread ownerThread;

    @Autowired
    public LedgerOwner(CashOwnerProperties props) {
        this(toSettings(props), Ledger::checkInvariants);
    }

    LedgerOwner(Settings settings, Consumer<Ledger> invariantCheck) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.invariantCheck = Objects.requireNonNull(invariantCheck, "invariantCheck");
        if (settings.maxPendingRequests() < 1) {
            throw new IllegalArgumentException("maxPendingRequests must be >= 1: " + settings.maxPendingRequests());
        }
        if (settings.shutdownDrain() == null || settings.shutdownDrain().isNegative()) {
            throw new IllegalArgumentException("shutdownDrain must not be negative: " + settings.shutdownDrain());
        }
    }

    /** For tests outside this package: same as the package-private constructor. */
    public static LedgerOwner forTests(Settings settings, Consumer<Ledger> invariantCheck) {
        return new LedgerOwner(settings, invariantCheck);
    }

    private static Settings toSettings(CashOwnerProperties props) {
        CashOwnerProperties.Owner owner = Objects.requireNonNull(props.owner(), "cash-owner.owner is not configured");
        return new Settings(owner.maxPendingRequests(), owner.checkInvariants(), owner.shutdownDrain());
    }

    private static ExecutorService newReplyExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ledger-reply-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------- public API

    /**
     * New outside work (gRPC requests). Fails fast with {@link OwnerBusyException}
     * when more than
     * {@code maxPendingRequests} requests are waiting to start.
     */
    public <T> CompletableFuture<T> request(Function<Ledger, T> job) {
        return submit(job, true);
    }

    /**
     * Follow-up work that must never be dropped: sequencer acks, stream
     * opened/lost, engine events. Not limited by
     * {@code maxPendingRequests}.
     */
    public <T> CompletableFuture<T> followUp(Function<Ledger, T> job) {
        return submit(job, false);
    }

    /** Jobs waiting, for logs and metrics. */
    public int queueDepth() {
        return queue.size();
    }

    /** True while running and not broken. */
    public boolean isHealthy() {
        return state == State.RUNNING && !broken;
    }

    // ---------------------------------------------------------------- submit

    private <T> CompletableFuture<T> submit(Function<Ledger, T> job, boolean isRequest) {
        Objects.requireNonNull(job, "job");
        if (Thread.currentThread() == ownerThread) {
            throw new IllegalStateException(
                    "a job must not call request()/followUp(): it would wait for the owner thread, which is itself");
        }
        synchronized (lock) {
            if (state != State.RUNNING || broken) {
                return CompletableFuture.failedFuture(unavailable());
            }
            if (isRequest && pendingRequests.incrementAndGet() > settings.maxPendingRequests()) {
                pendingRequests.decrementAndGet();
                return CompletableFuture.failedFuture(new OwnerBusyException(
                        "more than " + settings.maxPendingRequests() + " requests are waiting; nothing was done"));
            }
            CompletableFuture<T> future = new CompletableFuture<>();
            queue.add(new Task<>(job, future, isRequest));
            return future;
        }
    }

    private OwnerUnavailableException unavailable() {
        String why = broken ? "broken" : state == State.NEW ? "not started" : "stopping or stopped";
        return new OwnerUnavailableException("ledger owner is " + why + "; nothing was done");
    }

    // ---------------------------------------------------------------- owner loop

    private void loop() {
        log.info("ledger-owner thread started");
        try {
            while (true) {
                Task<?> task;
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    break; // only stop() interrupts us, after the drain time ran out
                }
                if (task == stopMarker) {
                    break;
                }
                if (abort) {
                    discard(task);
                    break;
                }
                if (!execute(task)) {
                    break;
                }
            }
        } finally {
            synchronized (lock) {
                if (state == State.RUNNING) {
                    broken = true; // the loop must only end through stop() or a broken ledger
                }
            }
            failQueued();
            log.info("ledger-owner thread stopped");
        }
    }

    /** Runs one job. Returns false when the loop must stop. */
    private <T> boolean execute(Task<T> task) {
        if (task.isRequest) {
            pendingRequests.decrementAndGet();
        }
        T result = null;
        Exception jobFailure = null;
        try {
            result = task.job.apply(ledger);
        } catch (Exception e) {
            jobFailure = e;
        } catch (Error e) {
            fatal(task, e);
            throw e;
        }

        if (settings.checkInvariants()) {
            try {
                invariantCheck.accept(ledger);
            } catch (Exception e) {
                if (jobFailure != null && jobFailure != e) {
                    e.addSuppressed(jobFailure);
                }
                fatal(task, e);
                return false;
            } catch (Error e) {
                fatal(task, e);
                throw e;
            }
        }

        if (jobFailure != null) {
            fail(task, jobFailure);
        } else {
            succeed(task, result);
        }
        return true;
    }

    /** The Ledger can no longer be trusted: stop taking work. */
    private void fatal(Task<?> task, Throwable cause) {
        synchronized (lock) {
            broken = true;
        }
        log.error("ledger-owner is broken, no more work will be accepted: {}", cause.toString(), cause);
        fail(task, cause);
        failQueued();
    }

    private void failQueued() {
        Task<?> task;
        while ((task = queue.poll()) != null) {
            if (task != stopMarker) {
                discard(task);
            }
        }
    }

    private void discard(Task<?> task) {
        if (task.isRequest) {
            pendingRequests.decrementAndGet();
        }
        fail(task, unavailable());
    }

    // ---------------------------------------------------------------- replies (off
    // the owner thread)

    private <T> void succeed(Task<T> task, T result) {
        reply(() -> task.future.complete(result));
    }

    private void fail(Task<?> task, Throwable cause) {
        reply(() -> task.future.completeExceptionally(cause));
    }

    private void reply(Runnable completion) {
        try {
            replyExecutor.execute(completion);
        } catch (RejectedExecutionException e) {
            completion.run(); // only after shutdown, when a stuck job finally finishes
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void start() {
        synchronized (lock) {
            if (state == State.RUNNING) {
                return;
            }
            if (state != State.NEW) {
                throw new IllegalStateException("ledger owner cannot be restarted");
            }
            Thread thread = new Thread(this::loop, "ledger-owner");
            thread.setDaemon(true); // a job stuck even after interrupt must not keep the JVM alive
            ownerThread = thread;
            state = State.RUNNING;
            thread.start();
        }
    }

    @Override
    public void stop() {
        Thread thread = ownerThread;
        if (thread != null && Thread.currentThread() == thread) {
            throw new IllegalStateException("a job must not stop the ledger owner");
        }
        synchronized (lock) {
            if (state == State.STOPPED) {
                return;
            }
            if (state == State.NEW) {
                state = State.STOPPED;
            } else if (state == State.RUNNING) {
                state = State.STOPPING;
                queue.add(stopMarker); // last in the queue: the loop ends after the jobs already accepted
            }
        }

        if (thread != null) {
            boolean interrupted = false;
            try {
                thread.join(settings.shutdownDrain().toMillis());
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (thread.isAlive()) {
                log.warn("ledger-owner did not finish within {}, interrupting; queued jobs are dropped",
                        settings.shutdownDrain());
                abort = true;
                thread.interrupt();
                failQueued();
                try {
                    thread.join(1000);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (thread.isAlive()) {
                    log.error("ledger-owner is still running a job after interrupt");
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        replyExecutor.shutdown();
        try {
            replyExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            state = State.STOPPED;
        }
    }

    @Override
    public boolean isRunning() {
        State current = state;
        return current == State.RUNNING || current == State.STOPPING;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
