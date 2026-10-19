package com.protrader.cash_owner.owner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.AccountView;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.owner.LedgerOwner.Settings;
import com.protrader.cash_owner.services.Ledger;

class LedgerOwnerTest {

    private static final long WAIT_SECONDS = 5;
    private static final int SYMBOL = 1;

    private LedgerOwner owner;

    @BeforeEach
    void setUp() {
        owner = startOwner(new Settings(10_000, true, Duration.ofSeconds(5)), Ledger::checkInvariants);
    }

    @AfterEach
    void tearDown() {
        owner.stop();
    }

    private static LedgerOwner startOwner(Settings settings, Consumer<Ledger> check) {
        LedgerOwner o = new LedgerOwner(settings, check);
        o.start();
        return o;
    }

    private void replaceOwner(Settings settings, Consumer<Ledger> check) {
        owner.stop();
        owner = startOwner(settings, check);
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) throws Exception {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS));
        return e.getCause();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // 1
    @Test
    void resultIsCompletedOnAReplyThreadNotTheOwnerThread() throws Exception {
        CountDownLatch callbackRegistered = new CountDownLatch(1);
        CompletableFuture<Integer> future = owner.request(l -> {
            await(callbackRegistered); // so the callback is attached before the future completes
            return 42;
        });
        CompletableFuture<String> callbackThread = new CompletableFuture<>();
        future.thenAccept(v -> callbackThread.complete(Thread.currentThread().getName()));
        callbackRegistered.countDown();

        assertEquals(42, get(future));
        String name = get(callbackThread);
        assertTrue(name.startsWith("ledger-reply-"), name);
    }

    // 2
    @Test
    void jobsFromOneThreadRunInOrder() throws Exception {
        List<Integer> seen = new ArrayList<>(); // only the owner thread touches it while jobs run
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            int n = i;
            futures.add(owner.followUp(l -> {
                seen.add(n);
                return null;
            }));
        }
        for (CompletableFuture<Void> f : futures) {
            get(f);
        }
        assertEquals(10_000, seen.size());
        for (int i = 0; i < 10_000; i++) {
            assertEquals(i, seen.get(i));
        }
    }

    // 3
    @Test
    void everyJobRunsOnTheOwnerThread() throws Exception {
        Set<String> names = new HashSet<>(); // only the owner thread touches it while jobs run
        ConcurrentLinkedQueue<CompletableFuture<Void>> all = new ConcurrentLinkedQueue<>();
        runThreads(8, t -> {
            for (int i = 0; i < 1_000; i++) {
                all.add(owner.followUp(l -> {
                    names.add(Thread.currentThread().getName());
                    return null;
                }));
            }
        });
        assertEquals(8_000, all.size());
        for (CompletableFuture<Void> f : all) {
            get(f);
        }
        assertEquals(Set.of("ledger-owner"), names);
    }

    // 4
    @RepeatedTest(20)
    void manyThreadsPlacingAndRefusingNeverChangeTotalMoney() throws Exception {
        // checkInvariants() scans every order, and this test creates 16,000 of them, so
        // the full check runs on
        // every 50th job (plus once at the end). Test 7 covers "check after every job".
        AtomicInteger jobsRun = new AtomicInteger(); // only the owner thread touches it
        replaceOwner(new Settings(10_000, true, Duration.ofSeconds(5)), l -> {
            if (jobsRun.incrementAndGet() % 50 == 0) {
                l.checkInvariants();
            }
        });
        int accounts = 5;
        long startCash = 1_000_000; // Rs 10,000 in paise
        long startShares = 100;
        for (int a = 1; a <= accounts; a++) {
            long id = a;
            get(owner.followUp(l -> {
                l.openAccount(id, startCash, Map.of(SYMBOL, startShares));
                return null;
            }));
        }

        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<CompletableFuture<Void>> refusals = new ConcurrentLinkedQueue<>();
        AtomicInteger locked = new AtomicInteger();
        runThreads(8, t -> {
            Random random = new Random(1234 + t);
            for (int i = 0; i < 2_000; i++) {
                long accountId = random.nextInt(accounts) + 1;
                Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                long price = random.nextInt(5_000) + 1;
                long qty = random.nextInt(100) + 1;
                String clientOrderId = "t" + t + "-" + i;
                try {
                    PlaceResult result = get(owner.request(
                            l -> l.place(accountId, clientOrderId, SYMBOL, side, price, qty)));
                    if (result instanceof PlaceResult.Locked) {
                        locked.incrementAndGet();
                        OrderKey key = new OrderKey(accountId, clientOrderId);
                        refusals.add(owner.followUp(l -> {
                            l.onRefused(key);
                            return null;
                        }));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }
        });
        for (CompletableFuture<Void> f : refusals) {
            get(f);
        }

        assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);
        assertTrue(locked.get() > 0);
        get(owner.request(l -> {
            l.checkInvariants();
            return null;
        }));
        for (int a = 1; a <= accounts; a++) {
            long id = a;
            AccountView view = get(owner.request(l -> l.account(id).orElseThrow()));
            assertEquals(startCash, view.freeCashMinor(), "free cash of account " + id);
            assertEquals(0, view.lockedCashMinor(), "locked cash of account " + id);
            assertEquals(startShares, view.holdings().get(SYMBOL).freeQty(), "free shares of account " + id);
            assertEquals(0, view.holdings().get(SYMBOL).lockedQty(), "locked shares of account " + id);
        }
    }

    // 5
    @Test
    void requestsOverTheLimitFailAtOnceButFollowUpsAreAccepted() throws Exception {
        replaceOwner(new Settings(3, true, Duration.ofSeconds(5)), Ledger::checkInvariants);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> first = owner.request(l -> {
            started.countDown();
            await(release);
            return 0;
        });
        await(started); // the first job is running, so it no longer counts as waiting

        List<CompletableFuture<Integer>> accepted = new ArrayList<>();
        accepted.add(first);
        for (int i = 1; i <= 3; i++) {
            int n = i;
            accepted.add(owner.request(l -> n));
        }
        CompletableFuture<Integer> busy = owner.request(l -> 99);
        assertTrue(busy.isDone());
        assertInstanceOf(OwnerBusyException.class, failureOf(busy));

        accepted.add(owner.followUp(l -> 4));
        release.countDown();
        for (int i = 0; i < accepted.size(); i++) {
            assertEquals(i, get(accepted.get(i)));
        }
        // the refused request was not counted: there is room again
        assertEquals(Integer.valueOf(7), get(owner.request(l -> 7)));
    }

    // 6
    @Test
    void aThrowingJobFailsOnlyItsOwnFuture() throws Exception {
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        CompletableFuture<Integer> bad = owner.request(l -> {
            throw boom;
        });
        CompletableFuture<Integer> good = owner.request(l -> 1);

        assertSame(boom, failureOf(bad));
        assertEquals(1, get(good));
        assertTrue(owner.isHealthy());
    }

    // 7
    @Test
    void aFailedInvariantCheckBreaksTheOwnerAndFailsClosed() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        IllegalStateException corrupted = new IllegalStateException("ledger corrupted");
        replaceOwner(new Settings(10_000, true, Duration.ofSeconds(5)), l -> {
            if (checks.incrementAndGet() == 3) {
                throw corrupted;
            }
        });
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Integer>> jobs = new ArrayList<>();
        jobs.add(owner.followUp(l -> {
            await(release);
            return 1;
        }));
        for (int i = 2; i <= 5; i++) {
            int n = i;
            jobs.add(owner.followUp(l -> n));
        }
        release.countDown();

        assertEquals(1, get(jobs.get(0)));
        assertEquals(2, get(jobs.get(1)));
        assertSame(corrupted, failureOf(jobs.get(2)));
        assertInstanceOf(OwnerUnavailableException.class, failureOf(jobs.get(3)));
        assertInstanceOf(OwnerUnavailableException.class, failureOf(jobs.get(4)));
        assertFalse(owner.isHealthy());
        assertInstanceOf(OwnerUnavailableException.class, failureOf(owner.request(l -> 6)));
        assertInstanceOf(OwnerUnavailableException.class, failureOf(owner.followUp(l -> 6)));
    }

    // 8
    @Test
    void requestsBeforeStartAndAfterStopFailUnavailable() throws Exception {
        LedgerOwner notStarted = new LedgerOwner(new Settings(10, true, Duration.ofSeconds(1)),
                Ledger::checkInvariants);
        assertFalse(notStarted.isHealthy());
        assertInstanceOf(OwnerUnavailableException.class, failureOf(notStarted.request(l -> 1)));
        assertInstanceOf(OwnerUnavailableException.class, failureOf(notStarted.followUp(l -> 1)));

        assertEquals(Integer.valueOf(1), get(owner.request(l -> 1)));
        owner.stop();
        assertFalse(owner.isRunning());
        assertInstanceOf(OwnerUnavailableException.class, failureOf(owner.request(l -> 1)));
        assertInstanceOf(OwnerUnavailableException.class, failureOf(owner.followUp(l -> 1)));
    }

    // 9
    @Test
    void callingTheOwnerFromInsideAJobThrows() throws Exception {
        CompletableFuture<Object> outer = owner.request(l -> owner.request(l2 -> 1));
        assertInstanceOf(IllegalStateException.class, failureOf(outer));
        CompletableFuture<Object> outerFollowUp = owner.followUp(l -> owner.followUp(l2 -> 1));
        assertInstanceOf(IllegalStateException.class, failureOf(outerFollowUp));
        assertTrue(owner.isHealthy());
    }

    // 10
    @Test
    void stopLetsQueuedJobsFinish() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Integer>> jobs = new ArrayList<>();
        jobs.add(owner.followUp(l -> {
            await(release);
            return -1;
        }));
        for (int i = 0; i < 100; i++) {
            int n = i;
            jobs.add(owner.followUp(l -> n));
        }

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(owner::stop);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (owner.isHealthy() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertFalse(owner.isHealthy());
        assertFalse(stopped.isDone()); // still waiting for the blocked job
        release.countDown();

        get(stopped);
        assertFalse(owner.isRunning());
        assertEquals(Integer.valueOf(-1), get(jobs.get(0)));
        for (int i = 0; i < 100; i++) {
            assertEquals(i, get(jobs.get(i + 1)));
        }
        assertInstanceOf(OwnerUnavailableException.class, failureOf(owner.request(l -> 1)));
    }

    // 11
    @Test
    void stopGivesUpAfterTheDrainTimeAndFailsQueuedJobs() throws Exception {
        replaceOwner(new Settings(10_000, true, Duration.ofMillis(200)), Ledger::checkInvariants);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        CompletableFuture<Integer> stuck = owner.followUp(l -> {
            started.countDown();
            await(neverReleased); // interrupted by stop()
            return 1;
        });
        List<CompletableFuture<Integer>> queued = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            queued.add(owner.followUp(l -> 2));
        }
        await(started);

        long begin = System.nanoTime();
        owner.stop();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue(tookMillis < 2_000, "stop() took " + tookMillis + " ms");
        assertNotNull(failureOf(stuck));
        for (CompletableFuture<Integer> f : queued) {
            assertInstanceOf(OwnerUnavailableException.class, failureOf(f));
        }
    }

    private interface ThreadBody {
        void run(int threadIndex) throws Exception;
    }

    /** Starts the threads together and waits for all of them. */
    private static void runThreads(int count, ThreadBody body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < count; t++) {
            int index = t;
            Thread thread = new Thread(() -> {
                try {
                    await(go);
                    body.run(index);
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "test-caller-" + t);
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
            assertFalse(thread.isAlive(), "caller thread did not finish");
        }
        assertTrue(failures.isEmpty(), () -> "caller threads failed: " + failures);
    }
}
