package com.protrader.cash_owner.services;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.protrader.cash_owner.grpc.AccountUpdateBus;
import com.protrader.cash_owner.grpc.ProtoMapper;
import com.protrader.cash_owner.models.AccountChange;
import com.protrader.proto.exchange.v1.AccountUpdate;

@Component
public class AccountUpdatePublisher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AccountUpdatePublisher.class);

    /**
     * Starts before and stops after the ledger owner, so changes it queued while
     * stopping are still sent.
     */
    private static final int PHASE = Integer.MIN_VALUE + 500;
    private static final long STOP_WAIT_MILLIS = 5_000;

    /** Queue entry that ends the thread; compared by identity, never sent. */
    private static final AccountChange STOP = new AccountChange(0, 0, null, null, 0, 0, 0, 0, null, null);

    private final Consumer<AccountUpdate> sink;
    private final LinkedBlockingQueue<AccountChange> queue = new LinkedBlockingQueue<>();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private final Object lock = new Object();
    private volatile boolean stopping;
    private Thread thread;

    @Autowired
    public AccountUpdatePublisher(AccountUpdateBus bus) {
        this(bus::publish);
    }

    /** For tests: send to any sink instead of the bus. */
    public AccountUpdatePublisher(Consumer<AccountUpdate> sink) {
        this.sink = sink;
    }

    /** Never blocks. Changes are sent in the order of the lists and calls. */
    public void submit(List<AccountChange> changes) {
        synchronized (lock) { // same lock as stop(): nothing can land after the STOP marker
            if (stopping) {
                dropped.addAndGet(changes.size());
                log.warn("account updates dropped, publisher is stopping: {} changes", changes.size());
                return;
            }
            queue.addAll(changes);
        }
    }

    private void loop() {
        log.info("account-update-publisher thread started");
        try {
            while (true) {
                AccountChange change;
                try {
                    change = queue.take();
                } catch (InterruptedException e) {
                    break; // only stop() interrupts us, after the wait ran out
                }
                if (change == STOP) {
                    break;
                }
                send(change);
            }
        } finally {
            log.info("account-update-publisher thread stopped, {} changes left unsent", queue.size());
        }
    }

    private void send(AccountChange change) {
        try {
            sink.accept(ProtoMapper.toAccountUpdate(change));
            sent.incrementAndGet();
        } catch (RuntimeException e) { // one bad change must not stop all updates
            failed.incrementAndGet();
            log.error("could not send account update for account {} seq {}: {}", change.accountId(),
                    change.accountSeq(), e.toString(), e);
        }
    }

    /** Changes waiting to be sent. */
    public int queueDepth() {
        return queue.size();
    }

    public long sentCount() {
        return sent.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (thread != null) {
                return;
            }
            Thread t = new Thread(this::loop, "account-update-publisher");
            t.setDaemon(true);
            thread = t;
            t.start();
        }
    }

    @Override
    public void stop() {
        Thread t;
        synchronized (lock) {
            t = thread;
            if (t == null || stopping) {
                return;
            }
            stopping = true;
            queue.add(STOP); // last in the queue: everything already accepted is sent first
        }
        boolean interrupted = false;
        try {
            t.join(STOP_WAIT_MILLIS);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (t.isAlive()) {
            log.warn("account-update-publisher did not finish within {} ms, interrupting", STOP_WAIT_MILLIS);
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (lock) {
            return thread != null && !stopping;
        }
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
