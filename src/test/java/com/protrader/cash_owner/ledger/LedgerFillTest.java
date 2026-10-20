package com.protrader.cash_owner.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.cash_owner.services.Ledger;

import org.junit.jupiter.api.Test;

/**
 * Step 5, piece 1: Ledger.onFill settles one side of one trade. Money is in
 * paise.
 */
class LedgerFillTest {

    private static final int MOOG = 1;
    private static final long RS_1_LAKH = 10_000_000; // ₹1,00,000

    private final Ledger ledger = new Ledger();

    /**
     * Places an order and marks it accepted by the sequencer with the given engine
     * order id.
     */
    private EngineRef openOrder(long accountId, String clientOrderId, Side side, long limitMinor, long qty,
            long orderId) {
        PlaceResult result = ledger.place(accountId, clientOrderId, MOOG, side, limitMinor, qty);
        assertTrue(result instanceof PlaceResult.Locked, "place failed: " + result);
        ledger.onAccepted(new OrderKey(accountId, clientOrderId), orderId);
        return new EngineRef(MOOG, orderId);
    }

    private AccountView account(long id) {
        return ledger.account(id).orElseThrow();
    }

    private OrderView order(long accountId, String clientOrderId) {
        return ledger.order(new OrderKey(accountId, clientOrderId)).orElseThrow();
    }

    // ------------------------------------------------------------------ buy side

    @Test
    void buyFilledAtItsLimitPaysFromLockAndGetsShares() {
        // Given account 21 has ₹1,00,000 and 500 MOOG, places buy 10 @ ₹101, accepted
        // as order 3
        ledger.openAccount(21, RS_1_LAKH, Map.of(MOOG, 500L));
        EngineRef b2 = openOrder(21, "b2", Side.BUY, 10_100, 10, 3);

        // When order 3 fills 10 @ ₹101
        ledger.onFill(b2, 10_100, 10);

        // Then free ₹98,990, locked ₹0, 510 free shares, order DONE
        AccountView a = account(21);
        assertEquals(9_899_000, a.freeCashMinor());
        assertEquals(0, a.lockedCashMinor());
        assertEquals(510, a.holdings().get(MOOG).freeQty());
        OrderView o = order(21, "b2");
        assertEquals(OrderStage.DONE, o.stage());
        assertEquals(0, o.remainingQty());
        assertEquals(10, o.filledQty());
        assertEquals(101_000, o.filledNotionalMinor());
        ledger.checkInvariants();
    }

    @Test
    void buyFilledBelowItsLimitGetsTheDifferenceBack() {
        // Given buy 10 @ limit ₹104 (₹1,040 locked)
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        EngineRef ref = openOrder(21, "b", Side.BUY, 10_400, 10, 7);

        // When it fills 10 @ ₹103
        ledger.onFill(ref, 10_300, 10);

        // Then ₹1,040 leaves the lock, ₹10 comes back to free: net cost ₹1,030
        AccountView a = account(21);
        assertEquals(0, a.lockedCashMinor());
        assertEquals(RS_1_LAKH - 103_000, a.freeCashMinor());
        assertEquals(103_000, order(21, "b").filledNotionalMinor());
        ledger.checkInvariants();
    }

    @Test
    void partialBuyFillKeepsTheRestLockedAtTheLimit() {
        // Given buy 10 @ ₹100 (₹1,000 locked)
        ledger.openAccount(21, RS_1_LAKH, Map.of(MOOG, 500L));
        EngineRef ref = openOrder(21, "b", Side.BUY, 10_000, 10, 5);

        // When 6 fill @ ₹98
        ledger.onFill(ref, 9_800, 6);

        // Then locked ₹400 (4 × ₹100), ₹12 back to free, +6 shares, order still OPEN
        AccountView a = account(21);
        assertEquals(40_000, a.lockedCashMinor());
        assertEquals(RS_1_LAKH - 100_000 + 1_200, a.freeCashMinor());
        assertEquals(506, a.holdings().get(MOOG).freeQty());
        OrderView o = order(21, "b");
        assertEquals(OrderStage.OPEN, o.stage());
        assertEquals(4, o.remainingQty());
        assertEquals(6, o.filledQty());
        assertEquals(58_800, o.filledNotionalMinor());
        ledger.checkInvariants();
    }

    @Test
    void firstTimeBuyerGetsANewHolding() {
        // Given an account with cash but no MOOG at all
        ledger.openAccount(30, RS_1_LAKH, Map.of());
        assertNull(account(30).holdings().get(MOOG));
        EngineRef ref = openOrder(30, "b", Side.BUY, 10_000, 10, 9);

        // When it fills 10 @ ₹100
        ledger.onFill(ref, 10_000, 10);

        // Then it has 10 free MOOG, none locked
        assertEquals(10, account(30).holdings().get(MOOG).freeQty());
        assertEquals(0, account(30).holdings().get(MOOG).lockedQty());
        ledger.checkInvariants();
    }

    // ------------------------------------------------------------------ sell side

    @Test
    void sellGivesLockedSharesAndReceivesTradePrice() {
        // Given account 1 has 500 MOOG, places sell 18 @ ₹100.50 (18 locked, 482 free)
        ledger.openAccount(1, RS_1_LAKH, Map.of(MOOG, 500L));
        EngineRef f1 = openOrder(1, "f1", Side.SELL, 10_050, 18, 7);

        // When 10 fill @ ₹101
        ledger.onFill(f1, 10_100, 10);

        // Then locked shares 8, free shares 482, free cash +₹1,010 (the extra 50
        // paise/share is just proceeds)
        AccountView a = account(1);
        assertEquals(8, a.holdings().get(MOOG).lockedQty());
        assertEquals(482, a.holdings().get(MOOG).freeQty());
        assertEquals(RS_1_LAKH + 101_000, a.freeCashMinor());
        assertEquals(0, a.lockedCashMinor());
        OrderView o = order(1, "f1");
        assertEquals(OrderStage.OPEN, o.stage());
        assertEquals(8, o.remainingQty());
        ledger.checkInvariants();
    }

    @Test
    void verifiedLiveRunSettlesBothAccounts() {
        // The 2026-10-19 live run: b1 10@100, b2 10@101, b3 5@101 (account 21); f1 sell
        // 18@100.50 (account 1)
        ledger.openAccount(21, RS_1_LAKH, Map.of(MOOG, 500L));
        ledger.openAccount(1, RS_1_LAKH, Map.of(MOOG, 500L));
        openOrder(21, "b1", Side.BUY, 10_000, 10, 1);
        EngineRef b2 = openOrder(21, "b2", Side.BUY, 10_100, 10, 3);
        EngineRef b3 = openOrder(21, "b3", Side.BUY, 10_100, 5, 5);
        EngineRef f1 = openOrder(1, "f1", Side.SELL, 10_050, 18, 7);

        // TRADE 10 @ 101 (f1 × b2), TRADE 5 @ 101 (f1 × b3): each trade = one onFill
        // per side
        ledger.onFill(b2, 10_100, 10);
        ledger.onFill(f1, 10_100, 10);
        ledger.onFill(b3, 10_100, 5);
        ledger.onFill(f1, 10_100, 5);

        AccountView buyer = account(21);
        assertEquals(RS_1_LAKH - 251_500, buyer.freeCashMinor()); // ₹97,485
        assertEquals(100_000, buyer.lockedCashMinor()); // b1 still waits
        assertEquals(515, buyer.holdings().get(MOOG).freeQty());

        AccountView seller = account(1);
        assertEquals(RS_1_LAKH + 151_500, seller.freeCashMinor());
        assertEquals(3, seller.holdings().get(MOOG).lockedQty()); // f1's 3 rest @ 100.50
        assertEquals(482, seller.holdings().get(MOOG).freeQty());

        assertEquals(OrderStage.DONE, order(21, "b2").stage());
        assertEquals(OrderStage.DONE, order(21, "b3").stage());
        assertEquals(OrderStage.OPEN, order(21, "b1").stage());
        assertEquals(3, order(1, "f1").remainingQty());
        ledger.checkInvariants();
    }

    // ------------------------------------------------------------------ must throw
    // and change nothing

    private void assertThrowsAndNothingChanges(Class<? extends Throwable> type, Runnable call) {
        Object before = snapshot();
        assertThrows(type, call::run);
        assertEquals(before, snapshot(), "a refused fill must leave the ledger untouched");
        ledger.checkInvariants();
    }

    private Object snapshot() {
        List<Object> s = new ArrayList<>();
        ledger.account(21).ifPresent(s::add);
        ledger.account(1).ifPresent(s::add);
        ledger.order(new OrderKey(21, "b")).ifPresent(s::add);
        ledger.order(new OrderKey(1, "s")).ifPresent(s::add);
        return s;
    }

    private EngineRef buy() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        return openOrder(21, "b", Side.BUY, 10_000, 10, 11);
    }

    private EngineRef sell() {
        ledger.openAccount(1, 0, Map.of(MOOG, 50L));
        return openOrder(1, "s", Side.SELL, 10_000, 10, 12);
    }

    @Test
    void zeroOrNegativePriceThrows() {
        EngineRef b = buy();
        EngineRef s = sell();
        assertThrowsAndNothingChanges(IllegalArgumentException.class, () -> ledger.onFill(b, 0, 5));
        assertThrowsAndNothingChanges(IllegalArgumentException.class, () -> ledger.onFill(b, -100, 5));
        assertThrowsAndNothingChanges(IllegalArgumentException.class, () -> ledger.onFill(s, 0, 5));
    }

    @Test
    void zeroOrNegativeQtyThrows() {
        EngineRef b = buy();
        assertThrowsAndNothingChanges(IllegalArgumentException.class, () -> ledger.onFill(b, 10_000, 0));
        assertThrowsAndNothingChanges(IllegalArgumentException.class, () -> ledger.onFill(b, 10_000, -1));
    }

    @Test
    void fillBiggerThanRemainingThrows() {
        EngineRef b = buy();
        ledger.onFill(b, 10_000, 7);
        assertThrowsAndNothingChanges(IllegalArgumentException.class, () -> ledger.onFill(b, 10_000, 4));
    }

    @Test
    void buyAboveLimitThrows() {
        EngineRef b = buy();
        assertThrowsAndNothingChanges(IllegalArgumentException.class, () -> ledger.onFill(b, 10_001, 5));
    }

    @Test
    void sellBelowLimitThrows() {
        EngineRef s = sell();
        assertThrowsAndNothingChanges(IllegalArgumentException.class, () -> ledger.onFill(s, 9_999, 5));
    }

    @Test
    void unknownRefThrows() {
        buy();
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onFill(new EngineRef(MOOG, 999), 10_000, 1));
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onFill(new EngineRef(2, 11), 10_000, 1));
    }

    @Test
    void fillOnAFinishedOrderThrows() {
        EngineRef b = buy();
        ledger.onFill(b, 10_000, 10);
        assertEquals(OrderStage.DONE, order(21, "b").stage());
        assertThrowsAndNothingChanges(IllegalStateException.class, () -> ledger.onFill(b, 10_000, 1));
    }

    @Test
    void orderNotYetLinkedCannotBeFilled() {
        // placed but no SubmitAck yet: no engine ref exists, so the fill finds nothing
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        ledger.place(21, "b", MOOG, Side.BUY, 10_000, 10);
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onFill(new EngineRef(MOOG, 11), 10_000, 1));
    }

    // ------------------------------------------------------------------ random:
    // money and shares are conserved

    @Test
    void randomTradesBetweenTwoAccountsConserveCashAndShares() {
        Random random = new Random(42);
        ledger.openAccount(21, 1_000_000_000L, Map.of(MOOG, 10_000L));
        ledger.openAccount(1, 1_000_000_000L, Map.of(MOOG, 10_000L));
        long totalCash = 2_000_000_000L;
        long totalShares = 20_000;
        long nextId = 1;

        for (int round = 0; round < 300; round++) {
            long buyLimit = 9_000 + random.nextInt(2_000);
            long sellLimit = 8_000 + random.nextInt((int) (buyLimit - 8_000) + 1); // sell limit <= buy limit
            long qty = 1 + random.nextInt(20);
            EngineRef buy = openOrder(21, "b" + round, Side.BUY, buyLimit, qty, nextId++);
            EngineRef sell = openOrder(1, "s" + round, Side.SELL, sellLimit, qty, nextId++);

            long left = qty;
            while (left > 0) {
                long q = 1 + random.nextInt((int) left);
                long price = sellLimit + random.nextInt((int) (buyLimit - sellLimit) + 1);
                ledger.onFill(buy, price, q);
                ledger.onFill(sell, price, q);
                left -= q;

                AccountView a = account(21);
                AccountView b = account(1);
                assertEquals(totalCash, a.freeCashMinor() + a.lockedCashMinor() + b.freeCashMinor()
                        + b.lockedCashMinor(), "cash must be conserved");
                assertEquals(totalShares, a.holdings().get(MOOG).freeQty() + a.holdings().get(MOOG).lockedQty()
                        + b.holdings().get(MOOG).freeQty() + b.holdings().get(MOOG).lockedQty(),
                        "shares must be conserved");
                ledger.checkInvariants();
            }
            assertEquals(OrderStage.DONE, order(21, "b" + round).stage());
            assertEquals(OrderStage.DONE, order(1, "s" + round).stage());
        }
        assertEquals(0, account(21).lockedCashMinor());
        assertEquals(0, account(1).holdings().get(MOOG).lockedQty());
    }
}
