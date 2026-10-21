package com.protrader.cash_owner.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.Outcome;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.services.Ledger;

import org.junit.jupiter.api.Test;

/** Step 5, piece 4: duplicates and gaps, using the engine's per-symbol seq. Money is in paise. */
class LedgerSeqTest {

    private static final int MOOG = 1;
    private static final int BNNA = 2;
    private static final long RS_1_LAKH = 10_000_000; // ₹1,00,000

    private final Ledger ledger = new Ledger();

    // ------------------------------------------------------------------ event builders (seq given explicitly)

    private static EngineEvent accepted(int symbol, long seq, long orderId, long owner, String clientOrderId,
            Side side, long price, long qty) {
        return new EngineEvent(symbol, seq, EngineEventKind.ACCEPTED, 0, orderId, 0, owner, 0, side, price, qty,
                clientOrderId);
    }

    private static EngineEvent rested(int symbol, long seq, long orderId) {
        return new EngineEvent(symbol, seq, EngineEventKind.RESTED, 0, orderId, 0, 0, 0, Side.BUY, 10_000L, 0, "");
    }

    /** A console seller (account 99, unknown) sells into our resting buy. */
    private static EngineEvent tradeAgainstConsole(int symbol, long seq, long ourBuyId, Long price, long qty) {
        return new EngineEvent(symbol, seq, EngineEventKind.TRADE, 0, 500 + seq, ourBuyId, 21, 99, Side.SELL, price,
                qty, "");
    }

    private void placeBuy(int symbol, String clientOrderId, long qty) {
        PlaceResult result = ledger.place(21, clientOrderId, symbol, Side.BUY, 10_000, qty);
        assertTrue(result instanceof PlaceResult.Locked, "place failed: " + result);
    }

    private AccountView account() {
        return ledger.account(21).orElseThrow();
    }

    private long filled(String clientOrderId) {
        return ledger.order(new OrderKey(21, clientOrderId)).orElseThrow().filledQty();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void duplicateTradeMovesMoneyOnlyOnce() {
        // Given our buy 10 @ ₹100 is accepted (seq 1)
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeBuy(MOOG, "b", 10);
        ledger.applyEngineEvent(accepted(MOOG, 1, 3, 21, "b", Side.BUY, 10_000, 10));

        // When the same TRADE of 4 (seq 2) arrives twice
        assertEquals(Outcome.APPLIED, ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 2, 3, 10_000L, 4)).outcome());
        AccountView afterFirst = account();
        assertEquals(Outcome.DUPLICATE, ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 2, 3, 10_000L, 4)).outcome());

        // Then 4 filled, not 8; balances exactly as after the first one
        assertEquals(4, filled("b"));
        assertEquals(afterFirst, account());
        assertEquals(4, account().holdings().get(MOOG).freeQty());
        ledger.checkInvariants();
    }

    @Test
    void bridgeRestartResendIsSkippedAndTheNextEventApplies() {
        // The example: lastApplied 8, bridge resends 7 and 8, then 9 arrives
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeBuy(MOOG, "b", 10);
        ledger.applyEngineEvent(accepted(MOOG, 6, 3, 21, "b", Side.BUY, 10_000, 10)); // first seen: 6
        ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 7, 3, 10_000L, 2));
        ledger.applyEngineEvent(rested(MOOG, 8, 3));
        assertEquals(8, ledger.lastApplied(MOOG).orElseThrow());

        assertEquals(Outcome.DUPLICATE, ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 7, 3, 10_000L, 2)).outcome());
        assertEquals(Outcome.DUPLICATE, ledger.applyEngineEvent(rested(MOOG, 8, 3)).outcome());
        assertEquals(Outcome.APPLIED, ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 9, 3, 10_000L, 3)).outcome());

        assertEquals(5, filled("b")); // 2 + 3, the resent 2 not counted again
        assertEquals(9, ledger.lastApplied(MOOG).orElseThrow());
        ledger.checkInvariants();
    }

    @Test
    void gapThrowsAndChangesNothing() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeBuy(MOOG, "b", 10);
        ledger.applyEngineEvent(accepted(MOOG, 1, 3, 21, "b", Side.BUY, 10_000, 10));
        AccountView before = account();

        // seq 2 and 3 never arrived
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 4, 3, 10_000L, 4)));

        assertTrue(e.getMessage().contains("gap"), e.getMessage());
        assertEquals(before, account());
        assertEquals(0, filled("b"));
        assertEquals(1, ledger.lastApplied(MOOG).orElseThrow());
        ledger.checkInvariants();
    }

    @Test
    void aFailedEventDoesNotUseUpItsSeq() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeBuy(MOOG, "b", 10);
        ledger.applyEngineEvent(accepted(MOOG, 1, 3, 21, "b", Side.BUY, 10_000, 10));

        // seq 2 fails (TRADE without a price) → lastApplied stays 1
        assertThrows(IllegalStateException.class,
                () -> ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 2, 3, null, 4)));
        assertEquals(1, ledger.lastApplied(MOOG).orElseThrow());

        // seq 2 again, correct this time → applied, not mistaken for a duplicate
        assertEquals(Outcome.APPLIED, ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 2, 3, 10_000L, 4)).outcome());
        assertEquals(4, filled("b"));
        assertEquals(2, ledger.lastApplied(MOOG).orElseThrow());
        ledger.checkInvariants();
    }

    @Test
    void eachSymbolIsCountedSeparately() {
        ledger.openAccount(21, RS_1_LAKH, Map.of());
        placeBuy(MOOG, "m", 10);
        placeBuy(BNNA, "n", 10);

        // MOOG 1, 2 and BNNA 1, 2 interleaved: none is a duplicate or a gap
        assertEquals(Outcome.APPLIED, ledger.applyEngineEvent(accepted(MOOG, 1, 3, 21, "m", Side.BUY, 10_000, 10)).outcome());
        assertEquals(Outcome.APPLIED, ledger.applyEngineEvent(accepted(BNNA, 1, 3, 21, "n", Side.BUY, 10_000, 10)).outcome());
        assertEquals(Outcome.APPLIED, ledger.applyEngineEvent(tradeAgainstConsole(MOOG, 2, 3, 10_000L, 1)).outcome());
        assertEquals(Outcome.APPLIED, ledger.applyEngineEvent(tradeAgainstConsole(BNNA, 2, 3, 10_000L, 2)).outcome());

        assertEquals(1, filled("m"));
        assertEquals(2, filled("n"));
        assertEquals(2, ledger.lastApplied(MOOG).orElseThrow());
        assertEquals(2, ledger.lastApplied(BNNA).orElseThrow());
        ledger.checkInvariants();
    }

    @Test
    void ignoredEventsStillMoveTheCounter() {
        // RESTED and events for orders that aren't ours advance lastApplied, so the next one isn't a "gap"
        List<EngineEvent> events = List.of(
                rested(MOOG, 1, 50),
                accepted(MOOG, 2, 51, 99, "console", Side.BUY, 10_000, 10), // not ours
                new EngineEvent(MOOG, 3, EngineEventKind.HALTED, 0, 51, 0, 99, 0, Side.BUY, 10_000L, 10, ""),
                new EngineEvent(MOOG, 4, EngineEventKind.OTHER, 0, 51, 0, 99, 0, Side.BUY, 10_000L, 10, ""));
        for (EngineEvent e : events) {
            assertEquals(Outcome.IGNORED, ledger.applyEngineEvent(e).outcome());
        }
        assertEquals(4, ledger.lastApplied(MOOG).orElseThrow());
        assertEquals(Outcome.IGNORED, ledger.applyEngineEvent(rested(MOOG, 5, 50)).outcome()); // not a gap
    }

    @Test
    void firstEventForASymbolIsTakenAsTheStartingPoint() {
        assertTrue(ledger.lastApplied(MOOG).isEmpty());
        ledger.applyEngineEvent(rested(MOOG, 812, 50));
        assertEquals(812, ledger.lastApplied(MOOG).orElseThrow());
        assertEquals(Outcome.DUPLICATE, ledger.applyEngineEvent(rested(MOOG, 811, 50)).outcome());
    }

    @Test
    void nonPositiveSeqThrows() {
        assertThrows(IllegalStateException.class, () -> ledger.applyEngineEvent(rested(MOOG, 0, 50)));
        assertThrows(IllegalStateException.class, () -> ledger.applyEngineEvent(rested(MOOG, -1, 50)));
        assertTrue(ledger.lastApplied(MOOG).isEmpty());
    }
}
