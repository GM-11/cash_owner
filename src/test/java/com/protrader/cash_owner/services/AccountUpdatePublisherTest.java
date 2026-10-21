package com.protrader.cash_owner.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.BalancesView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.proto.exchange.v1.AccountUpdate;

/** Step 5, piece 6: one publisher thread sends account changes in order and never makes the owner wait. */
class AccountUpdatePublisherTest {

    private static final String THREAD_NAME = "account-update-publisher";

    private AccountUpdatePublisher publisher;

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.stop();
        }
    }

    private static AccountChange change(long accountId, long accountSeq) {
        OrderView order = new OrderView(new OrderKey(accountId, "c"), 1, Side.BUY, 10_000, 10, 10, 0, 0, 5,
                OrderStage.OPEN, null);
        BalancesView balances = new BalancesView(900_000, 100_000);
        return new AccountChange(accountId, accountSeq, AccountChangeKind.NEW, order, 0, 0, 7, 1234, balances, null);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    private static boolean publisherThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals(THREAD_NAME) && t.isAlive());
    }

    @Test
    void changesAreSentInTheOrderTheyWereSubmitted() throws Exception {
        List<Long> seen = new CopyOnWriteArrayList<>();
        publisher = new AccountUpdatePublisher(u -> seen.add(u.getAccountSeq()));
        publisher.start();

        for (long seq = 1; seq <= 1_000; seq += 10) {
            List<AccountChange> batch = new ArrayList<>();
            for (long s = seq; s < seq + 10; s++) {
                batch.add(change(5, s));
            }
            publisher.submit(batch);
        }

        awaitTrue(() -> seen.size() == 1_000, "1,000 updates");
        for (int i = 0; i < 1_000; i++) {
            assertEquals(i + 1L, seen.get(i).longValue());
        }
        assertEquals(1_000, publisher.sentCount());
        assertEquals(0, publisher.failedCount());
    }

    @Test
    void submitReturnsImmediatelyEvenWhenTheBusIsBlocked() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Long> seen = new CopyOnWriteArrayList<>();
        publisher = new AccountUpdatePublisher(u -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            seen.add(u.getAccountSeq());
        });
        publisher.start();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (long i = 1; i <= 100; i++) {
                publisher.submit(List.of(change(5, i)));
            }
        });
        assertTrue(publisher.queueDepth() >= 99, "depth " + publisher.queueDepth()); // the sender holds the first
        assertTrue(seen.isEmpty());

        release.countDown();
        awaitTrue(() -> seen.size() == 100, "100 updates");
        assertEquals(0, publisher.queueDepth());
    }

    @Test
    void aFailedSendOrMappingIsCountedAndLaterChangesStillGo() throws Exception {
        List<Long> seen = new CopyOnWriteArrayList<>();
        publisher = new AccountUpdatePublisher(u -> {
            if (u.getAccountSeq() == 2) {
                throw new IllegalStateException("kafka said no");
            }
            seen.add(u.getAccountSeq());
        });
        publisher.start();
        AccountChange unmappable = new AccountChange(5, 3, AccountChangeKind.NEW, null, 0, 0, 7, 0, null, null);

        publisher.submit(List.of(change(5, 1), change(5, 2), unmappable, change(5, 4), change(5, 5)));

        awaitTrue(() -> seen.size() == 3, "the three good updates");
        assertEquals(List.of(1L, 4L, 5L), seen);
        assertEquals(2, publisher.failedCount());
        assertEquals(3, publisher.sentCount());
    }

    @Test
    void stopSendsWhatIsQueuedAndLeavesNoThread() throws Exception {
        List<Long> seen = new CopyOnWriteArrayList<>();
        publisher = new AccountUpdatePublisher(u -> seen.add(u.getAccountSeq()));
        publisher.start();
        assertTrue(publisherThreadAlive());
        assertTrue(publisher.isRunning());

        List<AccountChange> batch = new ArrayList<>();
        for (long s = 1; s <= 50; s++) {
            batch.add(change(5, s));
        }
        publisher.submit(batch);
        publisher.stop();

        assertEquals(50, seen.size());
        assertEquals(0, publisher.queueDepth());
        assertFalse(publisherThreadAlive());
        assertFalse(publisher.isRunning());

        publisher.submit(List.of(change(5, 51))); // after stop: not sent, counted
        assertEquals(1, publisher.droppedCount());
        assertEquals(50, seen.size());
    }
}
