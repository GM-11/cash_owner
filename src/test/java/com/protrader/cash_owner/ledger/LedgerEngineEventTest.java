package com.protrader.cash_owner.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.ApplyResult;
import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.Outcome;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.OrderView;
import com.protrader.cash_owner.services.Ledger;

import org.junit.jupiter.api.Test;

/**
 * Step 5, piece 3: Ledger.applyEngineEvent routes each engine event to our
 * order(s). Money is in paise.
 */
class LedgerEngineEventTest {

    private static final int MOOG = 1;
    private static final long RS_1_LAKH = 10_000_000; // ₹1,00,000

    private final Ledger ledger = new Ledger();
    private long seq = 0;

    // ------------------------------------------------------------------ event
    // builders

    private EngineEvent accepted(long orderId, long owner, String clientOrderId, Side side, long price, long qty) {
        return new EngineEvent(MOOG, ++seq, EngineEventKind.ACCEPTED, 0, orderId, 0, owner, 0, side, price, qty,
                clientOrderId);
    }

    private EngineEvent rested(long orderId, long owner, Side side, long price, long qty) {
        return new EngineEvent(MOOG, ++seq, EngineEventKind.RESTED, 0, orderId, 0, owner, 0, side, price, qty, "");
    }

    /** aggressor = the incoming order; buyer / seller = account ids. */
    private EngineEvent trade(long aggressorId, long passiveId, long buyer, long seller, Side aggressorSide,
            Long price, long qty) {
        return new EngineEvent(MOOG, ++seq, EngineEventKind.TRADE, 0, aggressorId, passiveId, buyer, seller,
                aggressorSide, price, qty, "");
    }

    private EngineEvent ended(EngineEventKind kind, long orderId, long owner, Side side, long qty,
            String clientOrderId) {
        return new EngineEvent(MOOG, ++seq, kind, 0, orderId, 0, owner, 0, side, 10_000L, qty, clientOrderId);
    }

    private EngineEvent rejected(long orderId, long owner, String clientOrderId, Side side, long qty) {
        return new EngineEvent(MOOG, ++seq, EngineEventKind.REJECTED, 0, orderId, 0, owner, 0, side, 10_000L, qty,
                clientOrderId);
    }

    // ------------------------------------------------------------------ helpers

    /** Locks and leaves the order SENT: no SubmitAck. */
    private OrderKey place(long accountId, String clientOrderId, Side side, long limitMinor, long qty) {
        PlaceResult result = ledger.place(accountId, clientOrderId, MOOG, side, limitMinor, qty);
        assertTrue(result instanceof PlaceResult.Locked, "place failed: " + result);
        return new OrderKey(accountId, clientOrderId);
    }

    /** Placed and linked through the engine's ACCEPTED event. */
    private void placeAndAccept(long accountId, String clientOrderId, Side side, long limitMinor, long qty,
            long orderId) {
        place(accountId, clientOrderId, side, limitMinor, qty);
        assertEquals(Outcome.APPLIED,
                ledger.applyEngineEvent(accepted(orderId, accountId, clientOrderId, side, limitMinor, qty)).outcome());
    }

    private AccountView account(long id) {
        return ledger.account(id).orElseThrow();
    }

    private OrderView order(long accountId, String clientOrderId) {
        return ledger.order(new OrderKey(accountId, clientOrderId)).orElseThrow();
    }

    private void assertThrowsAndNothingChanges(Runnable call, OrderKey... keys) {
        Object before = snapshot(keys);
        assertThrows(RuntimeException.class, call::run); // IllegalState or IllegalArgument: either way, refused
        assertEquals(before, snapshot(keys), "a refused event must leave the ledger untouched");
        ledger.checkInvariants();
    }

    private Object snapshot(OrderKey... keys) {
        List<Object> s = new ArrayList<>();
        ledger.account(21).ifPresent(s::add);
        ledger.account(1).ifPresent(s::add);
        for (OrderKey k : keys) {
            ledger.order(k).ifPresent(s::add);
        }
        return s;
    }

    // ------------------------------------------------------------------ ACCEPTED

    @Test
    void acceptedLinksASentOrder() {
        // Given placed, SubmitAck not here yet (SENT)
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        place(21, "b", Side.BUY, 10_000, 10);

        // When the engine's ACCEPTED arrives first
        Outcome outcome = ledger.applyEngineEvent(accepted(7, 21, "b", Side.BUY, 10_000, 10)).outcome();

        // Then it is OPEN and findable by engine id 7
        assertEquals(Outcome.APPLIED, outcome);
        assertEquals(OrderStage.OPEN, order(21, "b").stage());
        assertEquals(7, order(21, "b").orderId());
        assertTrue(ledger.orderByEngineRef(new EngineRef(MOOG, 7)).isPresent());
        ledger.checkInvariants();
    }

    @Test
    void acceptedHealsAnInDoubtOrder() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        OrderKey key = place(21, "b", Side.BUY, 10_000, 10);
        ledger.onNoAnswer(key);

        ledger.applyEngineEvent(accepted(7, 21, "b", Side.BUY, 10_000, 10));

        assertEquals(OrderStage.OPEN, order(21, "b").stage());
        assertEquals(7, order(21, "b").orderId());
        ledger.checkInvariants();
    }

    @Test
    void acceptedAfterTheAckChangesTheOrderNotAtAllAndOnlyAnnouncesItAsNew() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        OrderKey key = place(21, "b", Side.BUY, 10_000, 10);
        ledger.onAccepted(key, 7);
        OrderView orderBefore = order(21, "b");
        AccountView accountBefore = account(21);

        ApplyResult result = ledger.applyEngineEvent(accepted(7, 21, "b", Side.BUY, 10_000, 10));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(1, result.changes().size());
        assertEquals(AccountChangeKind.NEW, result.changes().get(0).kind());
        assertEquals(orderBefore, order(21, "b"));
        // the money is untouched; only the account's change counter moved
        assertEquals(accountBefore.freeCashMinor(), account(21).freeCashMinor());
        assertEquals(accountBefore.lockedCashMinor(), account(21).lockedCashMinor());
        assertEquals(accountBefore.accountSeq() + 1, account(21).accountSeq());
        ledger.checkInvariants();
    }

    @Test
    void acceptedNamingADifferentEngineIdThrows() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        OrderKey key = place(21, "b", Side.BUY, 10_000, 10);
        ledger.onAccepted(key, 7);

        assertThrowsAndNothingChanges(() -> ledger.applyEngineEvent(accepted(8, 21, "b", Side.BUY, 10_000, 10)),
                key);
    }

    @Test
    void acceptedForAnUnknownOrderIsIgnored() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        Object before = snapshot();

        assertEquals(Outcome.IGNORED,
                ledger.applyEngineEvent(accepted(7, 99, "console", Side.BUY, 10_000, 10)).outcome());

        assertEquals(before, snapshot());
    }

    // ------------------------------------------------------------------ the live
    // run, through events only

    @Test
    void verifiedLiveRunSettlesFromEventsAloneEvenWithNoSubmitAcks() {
        // 2026-10-19 run. No onAccepted calls at all: every order is linked by the
        // engine's ACCEPTED event.
        ledger.openAccount(21, RS_1_LAKH, Map.of(MOOG, 500L));
        ledger.openAccount(1, RS_1_LAKH, Map.of(MOOG, 500L));
        place(21, "b1", Side.BUY, 10_000, 10);
        place(21, "b2", Side.BUY, 10_100, 10);
        place(21, "b3", Side.BUY, 10_100, 5);
        place(1, "f1", Side.SELL, 10_050, 18);

        List<EngineEvent> events = List.of(
                accepted(1, 21, "b1", Side.BUY, 10_000, 10), rested(1, 21, Side.BUY, 10_000, 10),
                accepted(3, 21, "b2", Side.BUY, 10_100, 10), rested(3, 21, Side.BUY, 10_100, 10),
                accepted(5, 21, "b3", Side.BUY, 10_100, 5), rested(5, 21, Side.BUY, 10_100, 5),
                accepted(7, 1, "f1", Side.SELL, 10_050, 18),
                trade(7, 3, 21, 1, Side.SELL, 10_100L, 10), // f1 × b2
                trade(7, 5, 21, 1, Side.SELL, 10_100L, 5), // f1 × b3
                rested(7, 1, Side.SELL, 10_050, 3));
        for (EngineEvent e : events) {
            ledger.applyEngineEvent(e);
            ledger.checkInvariants();
        }

        AccountView buyer = account(21);
        assertEquals(RS_1_LAKH - 251_500, buyer.freeCashMinor()); // ₹97,485
        assertEquals(100_000, buyer.lockedCashMinor()); // b1 still resting
        assertEquals(515, buyer.holdings().get(MOOG).freeQty());

        AccountView seller = account(1);
        assertEquals(RS_1_LAKH + 151_500, seller.freeCashMinor());
        assertEquals(3, seller.holdings().get(MOOG).lockedQty());
        assertEquals(482, seller.holdings().get(MOOG).freeQty());

        assertEquals(DoneReason.FILLED, order(21, "b2").doneReason());
        assertEquals(DoneReason.FILLED, order(21, "b3").doneReason());
        assertEquals(OrderStage.OPEN, order(21, "b1").stage());
        assertEquals(3, order(1, "f1").remainingQty());
    }

    // ------------------------------------------------------------------ TRADE

    @Test
    void tradeWithOneUnknownSideSettlesOnlyOurs() {
        // Given our buy rests as engine order 3; the seller is an engine-console order
        // (id 50)
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeAndAccept(21, "b", Side.BUY, 10_000, 10, 3);

        Outcome outcome = ledger.applyEngineEvent(trade(50, 3, 21, 99, Side.SELL, 9_900L, 10)).outcome();

        assertEquals(Outcome.APPLIED, outcome);
        assertEquals(RS_1_LAKH - 99_000, account(21).freeCashMinor());
        assertEquals(0, account(21).lockedCashMinor());
        assertEquals(10, account(21).holdings().get(MOOG).freeQty());
        ledger.checkInvariants();
    }

    @Test
    void tradeWithNoKnownSideIsIgnored() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        Object before = snapshot();

        assertEquals(Outcome.IGNORED, ledger.applyEngineEvent(trade(50, 51, 98, 99, Side.BUY, 10_000L, 5)).outcome());

        assertEquals(before, snapshot());
    }

    @Test
    void tradeWithoutAPriceThrows() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeAndAccept(21, "b", Side.BUY, 10_000, 10, 3);

        assertThrowsAndNothingChanges(() -> ledger.applyEngineEvent(trade(50, 3, 21, 99, Side.SELL, null, 10)),
                new OrderKey(21, "b"));
    }

    @Test
    void tradeNamingTheWrongSideThrows() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        ledger.openAccount(1, RS_1_LAKH, Map.of(MOOG, 100L));
        placeAndAccept(21, "b", Side.BUY, 10_000, 10, 3);
        placeAndAccept(1, "s", Side.SELL, 10_000, 10, 4);

        // aggressor 4 is our SELL, but the event claims the aggressor was buying
        assertThrowsAndNothingChanges(() -> ledger.applyEngineEvent(trade(4, 3, 21, 1, Side.BUY, 10_000L, 10)),
                new OrderKey(21, "b"), new OrderKey(1, "s"));
    }

    @Test
    void tradeNamingTheWrongAccountThrows() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        ledger.openAccount(1, RS_1_LAKH, Map.of(MOOG, 100L));
        placeAndAccept(21, "b", Side.BUY, 10_000, 10, 3);
        placeAndAccept(1, "s", Side.SELL, 10_000, 10, 4);

        // sides right, but buyer/seller account ids swapped
        assertThrowsAndNothingChanges(() -> ledger.applyEngineEvent(trade(4, 3, 1, 21, Side.SELL, 10_000L, 10)),
                new OrderKey(21, "b"), new OrderKey(1, "s"));
    }

    @Test
    void tradeWhoseSecondSideIsInvalidSettlesNeitherSide() {
        // The reason for checkFill: aggressor fine, passive has only 4 left → the whole
        // trade is refused
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        ledger.openAccount(1, RS_1_LAKH, Map.of(MOOG, 100L));
        placeAndAccept(21, "b", Side.BUY, 10_000, 4, 3); // resting buy of 4
        placeAndAccept(1, "s", Side.SELL, 10_000, 10, 4); // incoming sell of 10

        assertThrowsAndNothingChanges(() -> ledger.applyEngineEvent(trade(4, 3, 21, 1, Side.SELL, 10_000L, 10)),
                new OrderKey(21, "b"), new OrderKey(1, "s"));
    }

    // ------------------------------------------------------------------ CANCELLED
    // / EXPIRED / REJECTED

    @Test
    void expiredEventUnlocksTheRest() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeAndAccept(21, "b", Side.BUY, 10_000, 10, 3);
        ledger.applyEngineEvent(trade(50, 3, 21, 99, Side.SELL, 10_000L, 4));

        assertEquals(Outcome.APPLIED,
                ledger.applyEngineEvent(ended(EngineEventKind.EXPIRED, 3, 21, Side.BUY, 6, "b")).outcome());

        assertEquals(0, account(21).lockedCashMinor());
        assertEquals(DoneReason.EXPIRED, order(21, "b").doneReason());
        ledger.checkInvariants();
    }

    @Test
    void cancelledEventUnlocksShares() {
        ledger.openAccount(1, 0, Map.of(MOOG, 100L));
        placeAndAccept(1, "s", Side.SELL, 10_000, 10, 4);

        assertEquals(Outcome.APPLIED,
                ledger.applyEngineEvent(ended(EngineEventKind.CANCELLED, 4, 1, Side.SELL, 10, "")).outcome());

        assertEquals(100, account(1).holdings().get(MOOG).freeQty());
        assertEquals(DoneReason.CANCELED, order(1, "s").doneReason());
        ledger.checkInvariants();
    }

    @Test
    void endedEventForAnUnknownOrderIsIgnored() {
        assertEquals(Outcome.IGNORED,
                ledger.applyEngineEvent(ended(EngineEventKind.EXPIRED, 50, 99, Side.BUY, 6, "x")).outcome());
        assertEquals(Outcome.IGNORED,
                ledger.applyEngineEvent(ended(EngineEventKind.CANCELLED, 50, 99, Side.BUY, 6, "")).outcome());
    }

    @Test
    void rejectedEventBeforeTheAckUnlocks() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        place(21, "abc", Side.BUY, 10_000, 10); // SENT

        assertEquals(Outcome.APPLIED, ledger.applyEngineEvent(rejected(9, 21, "abc", Side.BUY, 10)).outcome());

        assertEquals(RS_1_LAKH, account(21).freeCashMinor());
        assertEquals(DoneReason.REJECTED, order(21, "abc").doneReason());
        assertEquals(9, order(21, "abc").orderId());
        ledger.checkInvariants();
    }

    @Test
    void rejectedEventForAnUnknownOrderIsIgnored() {
        assertEquals(Outcome.IGNORED, ledger.applyEngineEvent(rejected(9, 99, "nope", Side.BUY, 10)).outcome());
    }

    // ------------------------------------------------------------------ nothing to
    // do

    @Test
    void restedHaltedAndOtherChangeNothing() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeAndAccept(21, "b", Side.BUY, 10_000, 10, 3);
        OrderKey key = new OrderKey(21, "b");
        Object before = snapshot(key);

        assertEquals(Outcome.IGNORED, ledger.applyEngineEvent(rested(3, 21, Side.BUY, 10_000, 10)).outcome());
        assertEquals(Outcome.IGNORED,
                ledger.applyEngineEvent(ended(EngineEventKind.HALTED, 3, 21, Side.BUY, 10, "")).outcome());
        assertEquals(Outcome.IGNORED,
                ledger.applyEngineEvent(ended(EngineEventKind.OTHER, 3, 21, Side.BUY, 10, "")).outcome());

        assertEquals(before, snapshot(key));
        ledger.checkInvariants();
    }
}
