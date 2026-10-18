package com.protrader.cash_owner.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class LedgerTest {

    private static final int SYMBOL = 1;

    @Test
    void placeBuyLocksCash() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());

        PlaceResult result = ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);

        assertTrue(result instanceof PlaceResult.Locked);
        Order order = ((PlaceResult.Locked) result).order();
        Account account = ledger.account(1).orElseThrow();
        assertEquals(400_000, account.freeCashMinor());
        assertEquals(100_000, account.lockedCashMinor());
        assertEquals(OrderStage.SENT, order.stage());
        ledger.checkInvariants();
    }

    @Test
    void placeBuyInsufficientCashRejectsAndLeavesBalancesUnchanged() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());

        PlaceResult result = ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 60);

        assertTrue(result instanceof PlaceResult.Rejected);
        PlaceResult.Rejected rejected = (PlaceResult.Rejected) result;
        assertEquals(RejectReason.INSUFFICIENT_CASH, rejected.reason());
        assertEquals(100_000, rejected.shortfallMinor());
        Account account = ledger.account(1).orElseThrow();
        assertEquals(500_000, account.freeCashMinor());
        assertEquals(0, account.lockedCashMinor());
        assertTrue(ledger.order(new OrderKey(1, "a")).isEmpty());
        ledger.checkInvariants();
    }

    @Test
    void placeSellInsufficientHoldingsRejectsAndLeavesNothingChanged() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 0, Map.of(SYMBOL, 3L));

        PlaceResult result = ledger.place(1, "a", SYMBOL, Side.SELL, 10_000, 5);

        assertTrue(result instanceof PlaceResult.Rejected);
        assertEquals(RejectReason.INSUFFICIENT_HOLDINGS, ((PlaceResult.Rejected) result).reason());
        Account account = ledger.account(1).orElseThrow();
        assertEquals(3, account.holdings().get(SYMBOL).freeQty());
        assertEquals(0, account.holdings().get(SYMBOL).lockedQty());
        ledger.checkInvariants();
    }

    @Test
    void placeSellLocksShares() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 0, Map.of(SYMBOL, 5L));

        PlaceResult result = ledger.place(1, "a", SYMBOL, Side.SELL, 10_000, 3);

        assertTrue(result instanceof PlaceResult.Locked);
        Account account = ledger.account(1).orElseThrow();
        assertEquals(2, account.holdings().get(SYMBOL).freeQty());
        assertEquals(3, account.holdings().get(SYMBOL).lockedQty());
        ledger.checkInvariants();
    }

    @Test
    void placingSameKeyTwiceReturnsSameOrderInstanceAndLocksOnce() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());

        PlaceResult first = ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);
        PlaceResult second = ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);

        Order order = ((PlaceResult.Locked) first).order();
        assertTrue(second instanceof PlaceResult.Duplicate);
        assertSame(order, ((PlaceResult.Duplicate) second).existing());
        Account account = ledger.account(1).orElseThrow();
        assertEquals(100_000, account.lockedCashMinor());
        ledger.checkInvariants();
    }

    @Test
    void sameClientOrderIdOnTwoAccountsBothLock() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());
        ledger.openAccount(2, 500_000, Map.of());

        PlaceResult first = ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);
        PlaceResult second = ledger.place(2, "a", SYMBOL, Side.BUY, 10_000, 10);

        assertTrue(first instanceof PlaceResult.Locked);
        assertTrue(second instanceof PlaceResult.Locked);
        ledger.checkInvariants();
    }

    @Test
    void onRefusedReleasesTheLock() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());
        ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);
        OrderKey key = new OrderKey(1, "a");

        ledger.onRefused(key);

        Account account = ledger.account(1).orElseThrow();
        assertEquals(500_000, account.freeCashMinor());
        assertEquals(0, account.lockedCashMinor());
        assertEquals(OrderStage.DONE, ledger.order(key).orElseThrow().stage());
        ledger.checkInvariants();
    }

    @Test
    void onNoAnswerMovesToInDoubtAndKeepsLock() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());
        ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);
        OrderKey key = new OrderKey(1, "a");

        ledger.onNoAnswer(key);

        Order order = ledger.order(key).orElseThrow();
        assertEquals(OrderStage.IN_DOUBT, order.stage());
        Account account = ledger.account(1).orElseThrow();
        assertEquals(400_000, account.freeCashMinor());
        assertEquals(100_000, account.lockedCashMinor());
        assertEquals(List.of(order), ledger.inDoubt());
        ledger.checkInvariants();
    }

    @Test
    void onAcceptedAfterInDoubtOpensTheOrder() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());
        ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);
        OrderKey key = new OrderKey(1, "a");
        ledger.onNoAnswer(key);

        ledger.onAccepted(key, 812);

        Order order = ledger.order(key).orElseThrow();
        assertEquals(OrderStage.OPEN, order.stage());
        assertEquals(812, order.orderId());
        assertTrue(ledger.inDoubt().isEmpty());
        assertEquals(order, ledger.orderByEngineRef(new EngineRef(SYMBOL, 812)).orElseThrow());
        ledger.checkInvariants();
    }

    @Test
    void repeatedOrLateAnswersAfterOpenAreIgnored() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());
        ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);
        OrderKey key = new OrderKey(1, "a");
        ledger.onAccepted(key, 812);

        ledger.onAccepted(key, 999);
        ledger.onRefused(key);
        ledger.onNoAnswer(key);

        Order order = ledger.order(key).orElseThrow();
        assertEquals(OrderStage.OPEN, order.stage());
        assertEquals(812, order.orderId());
        Account account = ledger.account(1).orElseThrow();
        assertEquals(400_000, account.freeCashMinor());
        assertEquals(100_000, account.lockedCashMinor());
        ledger.checkInvariants();
    }

    @Test
    void priceTimesQtyOverflowRejectsWithoutLocking() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());

        PlaceResult result = ledger.place(1, "a", SYMBOL, Side.BUY, Long.MAX_VALUE, 2);

        assertTrue(result instanceof PlaceResult.Rejected);
        PlaceResult.Rejected rejected = (PlaceResult.Rejected) result;
        assertEquals(RejectReason.INSUFFICIENT_CASH, rejected.reason());
        assertEquals(Long.MAX_VALUE, rejected.shortfallMinor());
        Account account = ledger.account(1).orElseThrow();
        assertEquals(500_000, account.freeCashMinor());
        assertEquals(0, account.lockedCashMinor());
        ledger.checkInvariants();
    }

    @Test
    void placeOnUnknownAccountRejects() {
        Ledger ledger = new Ledger();

        PlaceResult result = ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);

        assertTrue(result instanceof PlaceResult.Rejected);
        assertEquals(RejectReason.UNKNOWN_ACCOUNT, ((PlaceResult.Rejected) result).reason());
        ledger.checkInvariants();
    }

    @Test
    void openAccountTwiceThrows() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());

        assertThrows(IllegalStateException.class, () -> ledger.openAccount(1, 0, Map.of()));
    }

    @Test
    void refusingBuyAndSellRestoresStartingBalancesExactly() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of(SYMBOL, 5L));

        ledger.place(1, "buy", SYMBOL, Side.BUY, 10_000, 10);
        ledger.place(1, "sell", SYMBOL, Side.SELL, 10_000, 3);
        ledger.onRefused(new OrderKey(1, "buy"));
        ledger.onRefused(new OrderKey(1, "sell"));

        Account account = ledger.account(1).orElseThrow();
        assertEquals(500_000, account.freeCashMinor());
        assertEquals(0, account.lockedCashMinor());
        assertEquals(5, account.holdings().get(SYMBOL).freeQty());
        assertEquals(0, account.holdings().get(SYMBOL).lockedQty());
        ledger.checkInvariants();
    }

    @Test
    void acceptingTwoOrdersWithTheSameEngineRefThrows() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());
        ledger.place(1, "a", SYMBOL, Side.BUY, 10_000, 10);
        ledger.place(1, "b", SYMBOL, Side.BUY, 10_000, 10);
        ledger.onAccepted(new OrderKey(1, "a"), 812);

        assertThrows(IllegalStateException.class, () -> ledger.onAccepted(new OrderKey(1, "b"), 812));
    }

    @Test
    void placeWithNullSideThrows() {
        Ledger ledger = new Ledger();
        ledger.openAccount(1, 500_000, Map.of());

        assertThrows(IllegalArgumentException.class, () -> ledger.place(1, "a", SYMBOL, null, 10_000, 10));
    }

    @Test
    void answersForUnknownKeyAreNoOps() {
        Ledger ledger = new Ledger();
        OrderKey key = new OrderKey(99, "ghost");

        ledger.onAccepted(key, 1);
        ledger.onRefused(key);
        ledger.onNoAnswer(key);

        assertFalse(ledger.order(key).isPresent());
        ledger.checkInvariants();
    }

    @Test
    void randomizedOperationsPreserveInvariantsAndTotalMoney() {
        Ledger ledger = new Ledger();
        int accountCount = 5;
        int symbolCount = 3;
        long startingCash = 1_000_000;
        long startingShares = 1_000;

        long[] startingTotalCash = new long[accountCount];
        long[][] startingTotalShares = new long[accountCount][symbolCount];
        for (int a = 1; a <= accountCount; a++) {
            Map<Integer, Long> shares = new java.util.HashMap<>();
            for (int s = 0; s < symbolCount; s++) {
                shares.put(s, startingShares);
            }
            ledger.openAccount(a, startingCash, shares);
            startingTotalCash[a - 1] = startingCash;
            for (int s = 0; s < symbolCount; s++) {
                startingTotalShares[a - 1][s] = startingShares;
            }
        }

        Random random = new Random(42);
        List<OrderKey> liveKeys = new java.util.ArrayList<>();
        int nextClientOrderId = 0;
        long nextEngineOrderId = 1;

        for (int i = 0; i < 1000; i++) {
            int op = random.nextInt(4);
            long accountId = random.nextInt(accountCount) + 1;
            int symbol = random.nextInt(symbolCount);

            if (op == 0 || liveKeys.isEmpty()) {
                String clientOrderId = "c" + (nextClientOrderId++);
                Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                long price = random.nextInt(1000) + 1;
                long qty = random.nextInt(50) + 1;
                PlaceResult result = ledger.place(accountId, clientOrderId, symbol, side, price, qty);
                if (result instanceof PlaceResult.Locked) {
                    liveKeys.add(new OrderKey(accountId, clientOrderId));
                }
            } else {
                OrderKey key = liveKeys.get(random.nextInt(liveKeys.size()));
                int action = random.nextInt(3);
                if (action == 0) {
                    // Unique per call: order_id is only unique per partition, but a single
                    // ledger here stands in for every partition, so ids must not collide.
                    ledger.onAccepted(key, nextEngineOrderId++);
                } else if (action == 1) {
                    ledger.onRefused(key);
                } else {
                    ledger.onNoAnswer(key);
                }
            }

            ledger.checkInvariants();

            for (int a = 1; a <= accountCount; a++) {
                Account account = ledger.account((long) a).orElseThrow();
                long totalCash = account.freeCashMinor() + account.lockedCashMinor();
                assertEquals(startingTotalCash[a - 1], totalCash, "total cash changed for account " + a + " at step " + i);
                for (int s = 0; s < symbolCount; s++) {
                    Holding holding = account.holdings().get(s);
                    long totalShares = holding == null ? 0 : holding.freeQty() + holding.lockedQty();
                    assertEquals(startingTotalShares[a - 1][s], totalShares,
                            "total shares changed for account " + a + " symbol " + s + " at step " + i);
                }
            }
        }
    }
}
