package com.protrader.cash_owner.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.services.Ledger;

import org.junit.jupiter.api.Test;

/**
 * Step 5, piece 2: an order ends without (fully) trading. Money is in paise.
 */
class LedgerEndTest {

    private static final int MOOG = 1;
    private static final long RS_1_LAKH = 10_000_000; // ₹1,00,000

    private final Ledger ledger = new Ledger();

    private OrderKey place(long accountId, String clientOrderId, Side side, long limitMinor, long qty) {
        PlaceResult result = ledger.place(accountId, clientOrderId, MOOG, side, limitMinor, qty);
        assertTrue(result instanceof PlaceResult.Locked, "place failed: " + result);
        return new OrderKey(accountId, clientOrderId);
    }

    private EngineRef open(long accountId, String clientOrderId, Side side, long limitMinor, long qty, long orderId) {
        ledger.onAccepted(place(accountId, clientOrderId, side, limitMinor, qty), orderId);
        return new EngineRef(MOOG, orderId);
    }

    private AccountView account(long id) {
        return ledger.account(id).orElseThrow();
    }

    private OrderView order(OrderKey key) {
        return ledger.order(key).orElseThrow();
    }

    // ------------------------------------------------------------------ CANCELLED
    // / EXPIRED

    @Test
    void cancelledBuyUnlocksAllCash() {
        // Given buy 10 @ ₹100 (₹1,000 locked)
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        EngineRef ref = open(21, "b", Side.BUY, 10_000, 10, 1);

        // When the engine cancels the remaining 10
        ledger.onRemainderEnded(ref, 10, DoneReason.CANCELED);

        // Then all ₹1,000 is free again, order DONE / CANCELED with nothing remaining
        assertEquals(RS_1_LAKH, account(21).freeCashMinor());
        assertEquals(0, account(21).lockedCashMinor());
        OrderView o = order(new OrderKey(21, "b"));
        assertEquals(OrderStage.DONE, o.stage());
        assertEquals(DoneReason.CANCELED, o.doneReason());
        assertEquals(0, o.remainingQty());
        ledger.checkInvariants();
    }

    @Test
    void expiredAfterPartialFillUnlocksOnlyTheRest() {
        // Given buy 10 @ ₹100; 4 fill @ ₹99 (₹600 still locked, ₹4 refunded)
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        EngineRef ref = open(21, "b", Side.BUY, 10_000, 10, 1);
        ledger.onFill(ref, 9_900, 4);
        assertEquals(60_000, account(21).lockedCashMinor());

        // When the remaining 6 expire
        ledger.onRemainderEnded(ref, 6, DoneReason.EXPIRED);

        // Then locked ₹0; overall paid exactly 4 × ₹99 = ₹396; 4 shares kept; filled
        // stays 4
        assertEquals(0, account(21).lockedCashMinor());
        assertEquals(RS_1_LAKH - 39_600, account(21).freeCashMinor());
        assertEquals(4, account(21).holdings().get(MOOG).freeQty());
        OrderView o = order(new OrderKey(21, "b"));
        assertEquals(DoneReason.EXPIRED, o.doneReason());
        assertEquals(4, o.filledQty());
        assertEquals(0, o.remainingQty());
        ledger.checkInvariants();
    }

    @Test
    void cancelledSellUnlocksShares() {
        // Given 500 MOOG, sell 18 @ ₹100.50, 10 filled → 8 still locked
        ledger.openAccount(1, 0, Map.of(MOOG, 500L));
        EngineRef ref = open(1, "s", Side.SELL, 10_050, 18, 7);
        ledger.onFill(ref, 10_100, 10);

        // When the remaining 8 are cancelled
        ledger.onRemainderEnded(ref, 8, DoneReason.CANCELED);

        // Then 490 free, 0 locked, cash from the fill kept
        assertEquals(490, account(1).holdings().get(MOOG).freeQty());
        assertEquals(0, account(1).holdings().get(MOOG).lockedQty());
        assertEquals(101_000, account(1).freeCashMinor());
        ledger.checkInvariants();
    }

    @Test
    void fullyFilledOrderIsMarkedFilled() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        EngineRef ref = open(21, "b", Side.BUY, 10_000, 10, 1);
        ledger.onFill(ref, 10_000, 10);
        assertEquals(DoneReason.FILLED, order(new OrderKey(21, "b")).doneReason());
    }

    // ------------------------------------------------------------------ REJECTED

    @Test
    void rejectedWhileOpenUnlocksAndKeepsTheRecord() {
        // Given buy 10 @ ₹100, ack already arrived (OPEN, engine id 3)
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        OrderKey key = place(21, "abc", Side.BUY, 10_000, 10);
        ledger.onAccepted(key, 3);

        // When the engine rejects it
        ledger.onEngineRejected(key, 3);

        // Then cash is free, the order is DONE / REJECTED and still there
        assertEquals(RS_1_LAKH, account(21).freeCashMinor());
        assertEquals(0, account(21).lockedCashMinor());
        OrderView o = order(key);
        assertEquals(OrderStage.DONE, o.stage());
        assertEquals(DoneReason.REJECTED, o.doneReason());
        assertEquals(3, o.orderId());
        ledger.checkInvariants();
    }

    @Test
    void rejectedBeforeTheAckArrivesTakesTheEngineId() {
        // Given placed, still SENT (ack not here yet)
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        OrderKey key = place(21, "abc", Side.BUY, 10_000, 10);

        // When REJECTED arrives first
        ledger.onEngineRejected(key, 5);

        // Then DONE / REJECTED with engine id 5; the late ack is ignored and changes
        // nothing
        assertEquals(5, order(key).orderId());
        ledger.onAccepted(key, 5);
        assertEquals(OrderStage.DONE, order(key).stage());
        assertEquals(0, account(21).lockedCashMinor());
        ledger.checkInvariants();
    }

    @Test
    void rejectedWhileInDoubtUnlocks() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        OrderKey key = place(21, "abc", Side.BUY, 10_000, 10);
        ledger.onNoAnswer(key);
        assertEquals(OrderStage.IN_DOUBT, order(key).stage());

        ledger.onEngineRejected(key, 5);

        assertEquals(DoneReason.REJECTED, order(key).doneReason());
        assertEquals(RS_1_LAKH, account(21).freeCashMinor());
        ledger.checkInvariants();
    }

    @Test
    void rejectedSellUnlocksShares() {
        ledger.openAccount(1, 0, Map.of(MOOG, 500L));
        OrderKey key = place(1, "s", Side.SELL, 10_000, 20);

        ledger.onEngineRejected(key, 9);

        assertEquals(500, account(1).holdings().get(MOOG).freeQty());
        assertEquals(0, account(1).holdings().get(MOOG).lockedQty());
        ledger.checkInvariants();
    }

    @Test
    void retryAfterEngineRejectIsADuplicateAndLocksNothing() {
        // The reason we keep the record: the sequencer still has "abc" in its dedupe
        // table
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        OrderKey key = place(21, "abc", Side.BUY, 10_000, 10);
        ledger.onAccepted(key, 3);
        ledger.onEngineRejected(key, 3);

        PlaceResult retry = ledger.place(21, "abc", MOOG, Side.BUY, 10_000, 10);

        assertTrue(retry instanceof PlaceResult.Duplicate, "got " + retry);
        assertEquals(DoneReason.REJECTED, ((PlaceResult.Duplicate) retry).existing().doneReason());
        assertEquals(RS_1_LAKH, account(21).freeCashMinor());
        assertEquals(0, account(21).lockedCashMinor());
        ledger.checkInvariants();
    }

    // ------------------------------------------------------------------ must throw
    // and change nothing

    private void assertThrowsAndNothingChanges(Class<? extends Throwable> type, Runnable call) {
        Object before = snapshot();
        assertThrows(type, call::run);
        assertEquals(before, snapshot(), "a refused call must leave the ledger untouched");
        ledger.checkInvariants();
    }

    private Object snapshot() {
        List<Object> s = new ArrayList<>();
        ledger.account(21).ifPresent(s::add);
        ledger.order(new OrderKey(21, "b")).ifPresent(s::add);
        return s;
    }

    private EngineRef openBuy() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        return open(21, "b", Side.BUY, 10_000, 10, 11);
    }

    @Test
    void endedQtyDifferentFromRemainingThrows() {
        EngineRef ref = openBuy();
        ledger.onFill(ref, 10_000, 4); // 6 remain
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onRemainderEnded(ref, 5, DoneReason.EXPIRED));
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onRemainderEnded(ref, 10, DoneReason.EXPIRED));
    }

    @Test
    void endedWithWrongReasonThrows() {
        EngineRef ref = openBuy();
        assertThrowsAndNothingChanges(IllegalArgumentException.class,
                () -> ledger.onRemainderEnded(ref, 10, DoneReason.FILLED));
        assertThrowsAndNothingChanges(IllegalArgumentException.class,
                () -> ledger.onRemainderEnded(ref, 10, DoneReason.REJECTED));
        assertThrowsAndNothingChanges(IllegalArgumentException.class,
                () -> ledger.onRemainderEnded(ref, 10, null));
    }

    @Test
    void endingAnUnknownOrDoneOrderThrows() {
        EngineRef ref = openBuy();
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onRemainderEnded(new EngineRef(MOOG, 999), 10, DoneReason.CANCELED));
        ledger.onRemainderEnded(ref, 10, DoneReason.CANCELED);
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onRemainderEnded(ref, 0, DoneReason.CANCELED));
    }

    @Test
    void rejectingAnUnknownOrDoneOrderThrows() {
        EngineRef ref = openBuy();
        OrderKey key = new OrderKey(21, "b");
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onEngineRejected(new OrderKey(21, "nope"), 11));
        ledger.onRemainderEnded(ref, 10, DoneReason.CANCELED);
        assertThrowsAndNothingChanges(IllegalStateException.class, () -> ledger.onEngineRejected(key, 11));
    }

    @Test
    void rejectWithADifferentEngineIdThrows() {
        openBuy(); // OPEN with engine id 11
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onEngineRejected(new OrderKey(21, "b"), 12));
    }

    @Test
    void rejectOfAPartlyFilledOrderThrows() {
        EngineRef ref = openBuy();
        ledger.onFill(ref, 10_000, 3);
        assertThrowsAndNothingChanges(IllegalStateException.class,
                () -> ledger.onEngineRejected(new OrderKey(21, "b"), 11));
    }

    @Test
    void rejectWithNonPositiveEngineIdThrows() {
        openBuy();
        assertThrowsAndNothingChanges(IllegalArgumentException.class,
                () -> ledger.onEngineRejected(new OrderKey(21, "b"), 0));
    }
}
