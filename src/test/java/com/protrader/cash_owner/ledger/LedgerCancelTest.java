package com.protrader.cash_owner.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.ApplyResult;
import com.protrader.cash_owner.models.CancelDecision;
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

/** Cancel requests in the Ledger. A cancel request never moves money; only the engine's events do. Money in paise. */
class LedgerCancelTest {

    private static final int MOOG = 1;
    private static final long RS_1_LAKH = 10_000_000;
    private static final long BOB = 21;
    private static final long ALICE = 1;
    private static final long CONSOLE = 99; // an engine-console account the Ledger does not know
    private static final long PRICE_100 = 10_000; // ₹100

    private Ledger ledger;
    private long engineSeq;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.openAccount(BOB, RS_1_LAKH, Map.of());
        ledger.openAccount(ALICE, 0, Map.of(MOOG, 20L));
    }

    // ---------------------------------------------------------------- helpers

    private EngineRef open(long accountId, String clientOrderId, Side side, long limitPriceMinor, long qty,
            long orderId) {
        PlaceResult result = ledger.place(accountId, clientOrderId, MOOG, side, limitPriceMinor, qty);
        assertTrue(result instanceof PlaceResult.Locked, "place failed: " + result);
        OrderKey key = new OrderKey(accountId, clientOrderId);
        ledger.onAccepted(key, orderId);
        return new EngineRef(MOOG, orderId);
    }

    private EngineEvent event(EngineEventKind kind, long orderId, long passiveId, long owner, long other, Side side,
            Long priceMinor, long qty) {
        return new EngineEvent(MOOG, ++engineSeq, kind, 0, orderId, passiveId, owner, other, side, priceMinor, qty,
                "");
    }

    private ApplyResult apply(EngineEvent event) {
        ApplyResult result = ledger.applyEngineEvent(event);
        assertEquals(Outcome.APPLIED, result.outcome());
        return result;
    }

    private ApplyResult cancelled(long orderId, long owner, long qty) {
        return apply(event(EngineEventKind.CANCELLED, orderId, 0, owner, 0, null, null, qty));
    }

    /** Our resting buy (the passive side) is hit by a console seller. */
    private ApplyResult consoleSellsInto(long buyOrderId, long buyer, long priceMinor, long qty) {
        return apply(event(EngineEventKind.TRADE, 500 + engineSeq, buyOrderId, buyer, CONSOLE, Side.SELL, priceMinor,
                qty));
    }

    private AccountView account(long accountId) {
        return ledger.account(accountId).orElseThrow();
    }

    private OrderView order(String clientOrderId, long accountId) {
        return ledger.order(new OrderKey(accountId, clientOrderId)).orElseThrow();
    }

    private static List<AccountChangeKind> kinds(ApplyResult result) {
        return result.changes().stream().map(AccountChange::kind).toList();
    }

    // ---------------------------------------------------------------- requestCancel

    @Test
    void givenAnOpenOrder_whenItsOwnerCancels_thenSendAndNothingElseChanges() {
        // Given Bob's buy 10 @ ₹100 is open
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        AccountView before = account(BOB);

        // When Bob asks to cancel it
        CancelDecision decision = ledger.requestCancel(BOB, ref);

        // Then the cancel is to be sent and the order is flagged; no money moved, account_seq unchanged
        assertEquals(CancelDecision.SEND, decision);
        assertTrue(order("b1", BOB).cancelPending());
        assertEquals(OrderStage.OPEN, order("b1", BOB).stage());
        assertEquals(before, account(BOB));
        assertEquals(before.accountSeq(), account(BOB).accountSeq());
        ledger.checkInvariants();
    }

    @Test
    void givenAnOpenOrder_whenAnotherAccountCancels_thenNotFoundAndNothingChanges() {
        // Given Bob's open order
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        AccountView bobBefore = account(BOB);

        // When Alice tries to cancel it
        CancelDecision decision = ledger.requestCancel(ALICE, ref);

        // Then she is told it does not exist, and Bob's order is not flagged
        assertEquals(CancelDecision.NOT_FOUND, decision);
        assertFalse(order("b1", BOB).cancelPending());
        assertEquals(bobBefore, account(BOB));
    }

    @Test
    void givenNoSuchEngineRef_whenCancelled_thenNotFound() {
        assertEquals(CancelDecision.NOT_FOUND, ledger.requestCancel(BOB, new EngineRef(MOOG, 12345)));
    }

    @Test
    void givenAnOrderForAnotherSymbol_whenCancelledWithTheWrongSymbol_thenNotFound() {
        open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);

        assertEquals(CancelDecision.NOT_FOUND, ledger.requestCancel(BOB, new EngineRef(MOOG + 1, 1)));
        assertFalse(order("b1", BOB).cancelPending());
    }

    @Test
    void givenADoneOrder_whenCancelled_thenClosed() {
        // Given Bob's buy 10 is completely filled
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        ledger.onFill(ref, PRICE_100, 10);
        assertEquals(OrderStage.DONE, order("b1", BOB).stage());

        // When Bob cancels it
        // Then the order is closed, and no flag is set
        assertEquals(CancelDecision.CLOSED, ledger.requestCancel(BOB, ref));
        assertFalse(order("b1", BOB).cancelPending());
        ledger.checkInvariants();
    }

    @Test
    void givenASentOrder_whenCancelled_thenNotFoundBecauseItHasNoEngineIdYet() {
        // Given an order that was placed but not acked (SENT)
        ledger.place(BOB, "b1", MOOG, Side.BUY, PRICE_100, 10);

        // When Bob cancels with any engine id
        // Then there is nothing to find
        assertEquals(CancelDecision.NOT_FOUND, ledger.requestCancel(BOB, new EngineRef(MOOG, 1)));
        assertFalse(order("b1", BOB).cancelPending());
    }

    @Test
    void givenAnInDoubtOrder_whenCancelled_thenNotFoundBecauseItHasNoEngineIdYet() {
        ledger.place(BOB, "b1", MOOG, Side.BUY, PRICE_100, 10);
        ledger.onNoAnswer(new OrderKey(BOB, "b1"));
        assertEquals(OrderStage.IN_DOUBT, order("b1", BOB).stage());

        assertEquals(CancelDecision.NOT_FOUND, ledger.requestCancel(BOB, new EngineRef(MOOG, 1)));
        assertFalse(order("b1", BOB).cancelPending());
    }

    @Test
    void givenACancelAlreadyPending_whenCancelledAgain_thenSendAgainAndStillOneFlag() {
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);

        assertEquals(CancelDecision.SEND, ledger.requestCancel(BOB, ref));
        assertEquals(CancelDecision.SEND, ledger.requestCancel(BOB, ref));

        assertTrue(order("b1", BOB).cancelPending());
        ledger.checkInvariants();
    }

    // ---------------------------------------------------------------- engine events after a cancel request

    @Test
    void givenAPendingCancel_whenTheEngineCancelsTheRemaining10_thenLockIsReleasedAndFlagCleared() {
        // Given Bob's buy 10 @ ₹100 (₹1,000 locked) with a cancel pending
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        ledger.requestCancel(BOB, ref);
        assertEquals(100_000, account(BOB).lockedCashMinor());

        // When the engine reports CANCELLED for 10
        ApplyResult result = cancelled(1, BOB, 10);

        // Then the ₹1,000 is free again, the change is CANCELED, the flag is gone
        assertEquals(List.of(AccountChangeKind.CANCELED), kinds(result));
        assertEquals(RS_1_LAKH, account(BOB).freeCashMinor());
        assertEquals(0, account(BOB).lockedCashMinor());
        assertEquals(OrderStage.DONE, order("b1", BOB).stage());
        assertEquals(DoneReason.CANCELED, order("b1", BOB).doneReason());
        assertFalse(order("b1", BOB).cancelPending());
        ledger.checkInvariants();
    }

    @Test
    void givenAPendingCancelAndAPartialFill_whenTheEngineCancelsTheRest_thenCashIsReleasedOnlyAtTheCancelled() {
        // Given buy 10 @ ₹100 with a cancel pending
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        ledger.requestCancel(BOB, ref);

        // When 6 trade @ ₹100: ₹400 is still locked (cancel did not unlock anything)
        ApplyResult partial = consoleSellsInto(1, BOB, PRICE_100, 6);
        assertEquals(List.of(AccountChangeKind.PARTIAL_FILL), kinds(partial));
        assertEquals(40_000, account(BOB).lockedCashMinor());
        assertTrue(order("b1", BOB).cancelPending());

        // And then the engine cancels the remaining 4
        ApplyResult cancelled = cancelled(1, BOB, 4);

        // Then ₹400 is released only now
        assertEquals(List.of(AccountChangeKind.CANCELED), kinds(cancelled));
        assertEquals(0, account(BOB).lockedCashMinor());
        assertEquals(RS_1_LAKH - 60_000, account(BOB).freeCashMinor());
        assertFalse(order("b1", BOB).cancelPending());
        ledger.checkInvariants();
    }

    @Test
    void givenAPendingCancel_whenATradeFillsTheWholeOrder_thenFillThenCancelRejectedWithConsecutiveSeq() {
        // Given buy 10 with a cancel pending
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        ledger.requestCancel(BOB, ref);
        long seqBefore = account(BOB).accountSeq();

        // When a trade fills all 10 (the cancel reached the engine too late)
        ApplyResult result = consoleSellsInto(1, BOB, PRICE_100, 10);

        // Then FILL, then CANCEL_REJECTED for the same order, with consecutive account_seq
        assertEquals(List.of(AccountChangeKind.FILL, AccountChangeKind.CANCEL_REJECTED), kinds(result));
        AccountChange fill = result.changes().get(0);
        AccountChange cancelRejected = result.changes().get(1);
        assertEquals(seqBefore + 1, fill.accountSeq());
        assertEquals(seqBefore + 2, cancelRejected.accountSeq());
        assertEquals(fill.order().key(), cancelRejected.order().key());
        assertEquals(0, cancelRejected.fillQty());
        assertEquals(0, cancelRejected.fillPriceMinor());
        assertNotNull(cancelRejected.balances());
        assertEquals(account(BOB).freeCashMinor(), cancelRejected.balances().freeCashMinor());
        assertEquals(null, cancelRejected.position());
        assertFalse(order("b1", BOB).cancelPending());
        assertEquals(OrderStage.DONE, order("b1", BOB).stage());
        assertEquals(DoneReason.FILLED, order("b1", BOB).doneReason());
        ledger.checkInvariants();
    }

    @Test
    void givenAPendingCancelOnBothSides_whenOneTradeFillsBoth_thenFourChangesInAggressorThenPassiveOrder() {
        // Given Alice sells 10 and Bob buys 10 @ ₹100, both with a cancel pending
        EngineRef aliceRef = open(ALICE, "s1", Side.SELL, PRICE_100, 10, 1);
        EngineRef bobRef = open(BOB, "b1", Side.BUY, PRICE_100, 10, 2);
        ledger.requestCancel(ALICE, aliceRef);
        ledger.requestCancel(BOB, bobRef);

        // When Alice's sell (the aggressor, id 1) trades against Bob's resting buy (id 2), both fully
        ApplyResult result = apply(event(EngineEventKind.TRADE, 1, 2, BOB, ALICE, Side.SELL, PRICE_100, 10));

        // Then: aggressor FILL, aggressor CANCEL_REJECTED, passive FILL, passive CANCEL_REJECTED
        assertEquals(List.of(AccountChangeKind.FILL, AccountChangeKind.CANCEL_REJECTED, AccountChangeKind.FILL,
                AccountChangeKind.CANCEL_REJECTED), kinds(result));
        List<Long> accounts = result.changes().stream().map(AccountChange::accountId).toList();
        assertEquals(List.of(ALICE, ALICE, BOB, BOB), accounts);
        assertEquals(result.changes().get(0).accountSeq() + 1, result.changes().get(1).accountSeq());
        assertEquals(result.changes().get(2).accountSeq() + 1, result.changes().get(3).accountSeq());
        assertFalse(order("s1", ALICE).cancelPending());
        assertFalse(order("b1", BOB).cancelPending());
        ledger.checkInvariants();
    }

    @Test
    void givenAPendingCancel_whenOnlyPartiallyFilled_thenNoCancelRejected() {
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        ledger.requestCancel(BOB, ref);

        ApplyResult result = consoleSellsInto(1, BOB, PRICE_100, 4);

        assertEquals(List.of(AccountChangeKind.PARTIAL_FILL), kinds(result));
        assertTrue(order("b1", BOB).cancelPending());
        assertEquals(OrderStage.OPEN, order("b1", BOB).stage());
    }

    @Test
    void givenAPendingSellCancel_whenTheEngineCancels_thenSharesAreFreeAgainAndThePositionIsReported() {
        // Given Alice (20 shares) sells 6, with a cancel pending
        EngineRef ref = open(ALICE, "s1", Side.SELL, PRICE_100, 6, 1);
        ledger.requestCancel(ALICE, ref);
        assertEquals(6, account(ALICE).holdings().get(MOOG).lockedQty());

        // When the engine cancels the 6
        ApplyResult result = cancelled(1, ALICE, 6);

        // Then all 20 are free, none locked, and the change carries the position (it is a sell)
        assertEquals(20, account(ALICE).holdings().get(MOOG).freeQty());
        assertEquals(0, account(ALICE).holdings().get(MOOG).lockedQty());
        AccountChange change = result.changes().get(0);
        assertEquals(AccountChangeKind.CANCELED, change.kind());
        assertNotNull(change.position());
        assertEquals(20, change.position().freeQty());
        ledger.checkInvariants();
    }

    @Test
    void givenAPendingSellCancel_whenATradeFillsIt_thenCancelRejectedCarriesThePositionToo() {
        // the "any change to a sell order carries the position" rule still applies
        EngineRef ref = open(ALICE, "s1", Side.SELL, PRICE_100, 6, 1);
        ledger.requestCancel(ALICE, ref);

        ApplyResult result = apply(event(EngineEventKind.TRADE, 7, 1, CONSOLE, ALICE, Side.BUY, PRICE_100, 6));

        assertEquals(List.of(AccountChangeKind.FILL, AccountChangeKind.CANCEL_REJECTED), kinds(result));
        assertNotNull(result.changes().get(1).position());
        assertEquals(14, result.changes().get(1).position().qty());
    }

    // ---------------------------------------------------------------- onCancelRefused

    @Test
    void givenAPendingCancel_whenTheSequencerRefusedIt_thenTheFlagIsCleared() {
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        ledger.requestCancel(BOB, ref);

        ledger.onCancelRefused(ref);

        assertFalse(order("b1", BOB).cancelPending());
        assertEquals(OrderStage.OPEN, order("b1", BOB).stage());
        assertEquals(100_000, account(BOB).lockedCashMinor());
    }

    @Test
    void givenADoneOrder_whenALateRefusalArrives_thenNothingHappens() {
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        ledger.requestCancel(BOB, ref);
        cancelled(1, BOB, 10);
        AccountView before = account(BOB);
        OrderView orderBefore = order("b1", BOB);

        ledger.onCancelRefused(ref);
        ledger.onCancelRefused(new EngineRef(MOOG, 777)); // unknown ref: also nothing

        assertEquals(before, account(BOB));
        assertEquals(orderBefore, order("b1", BOB));
    }

    // ---------------------------------------------------------------- invariant

    @Test
    void givenACancelPending_whenTheLowLevelOnFillFinishesTheOrder_thenCancelPendingIsClearedAndInvariantsHold() {
        // Given a cancel pending, then the order filled through the low-level entry point (skipping the trade path)
        EngineRef ref = open(BOB, "b1", Side.BUY, PRICE_100, 10, 1);
        ledger.requestCancel(BOB, ref);
        ledger.onFill(ref, PRICE_100, 10);

        // Then DONE implies no cancel pending
        assertEquals(false, order("b1", BOB).cancelPending());
        ledger.checkInvariants();
    }
}
