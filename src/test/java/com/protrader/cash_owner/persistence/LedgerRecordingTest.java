package com.protrader.cash_owner.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.ApplyResult;
import com.protrader.cash_owner.models.CancelDecision;
import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.OrderStage;
import com.protrader.cash_owner.models.PlaceResult;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.services.Ledger;

class LedgerRecordingTest {

    private static final OrderKey BUY = new OrderKey(1, "b1");
    private static final OrderKey SELL = new OrderKey(2, "s1");
    private static final EngineRef BUY_REF = new EngineRef(1, 41);
    private static final EngineRef SELL_REF = new EngineRef(1, 42);

    private final List<LedgerCommand> recorded = new ArrayList<>();
    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.setRecorder(recorded::add);
    }

    private static EngineEvent event(EngineEventKind kind, long seq, long orderId, long passiveId, long owner,
            long other, Side side, Long price, long qty, String clientOrderId) {
        return new EngineEvent(1, seq, kind, 0, orderId, passiveId, owner, other, side, price, qty, clientOrderId);
    }

    /** Runs the action and checks it recorded exactly {@code count} commands, returning them. */
    private List<LedgerCommand> recording(int count, Runnable action) {
        int before = recorded.size();
        action.run();
        assertEquals(count, recorded.size() - before);
        return List.copyOf(recorded.subList(before, recorded.size()));
    }

    private void openBuyerAndSeller() {
        ledger.openAccount(1, 1_000_000, Map.of());
        ledger.openAccount(2, 0, Map.of(1, 100L));
    }

    private void openBoth() {
        openBuyerAndSeller();
        ledger.place(1, "b1", 1, Side.BUY, 100, 10);
        ledger.onAccepted(BUY, 41);
        ledger.place(2, "s1", 1, Side.SELL, 100, 10);
        ledger.onAccepted(SELL, 42);
        recorded.clear();
    }

    @Test
    void givenInputsThatChangeState_whenApplied_thenEachRecordsExactlyOneCommand() {
        recording(1, () -> ledger.openAccount(1, 1_000_000, Map.of()));
        recording(1, () -> ledger.openAccount(2, 0, Map.of(1, 100L)));
        assertInstanceOf(LedgerCommand.OpenAccount.class, recorded.get(0));

        recording(1, () -> assertInstanceOf(PlaceResult.Locked.class,
                ledger.place(1, "b1", 1, Side.BUY, 100, 10)));
        recording(1, () -> ledger.onNoAnswer(BUY));
        recording(1, () -> ledger.onAccepted(BUY, 41)); // from IN_DOUBT
        recording(1, () -> ledger.place(2, "s1", 1, Side.SELL, 100, 10));
        recording(1, () -> ledger.applyEngineEvent(
                event(EngineEventKind.ACCEPTED, 1, 42, 0, 2, 0, Side.SELL, 100L, 10, "s1")));

        recording(1, () -> assertEquals(CancelDecision.SEND, ledger.requestCancel(1, BUY_REF)));
        recording(1, () -> ledger.onCancelRefused(BUY_REF));

        List<LedgerCommand> trade = recording(1, () -> assertInstanceOf(ApplyResult.class,
                ledger.applyEngineEvent(event(EngineEventKind.TRADE, 2, 42, 41, 1, 2, Side.SELL, 100L, 4, ""))));
        assertInstanceOf(LedgerCommand.EngineEventApplied.class, trade.get(0));
        recording(1, () -> ledger.onFill(BUY_REF, 100, 2));
        recording(1, () -> ledger.onRemainderEnded(BUY_REF, 4, DoneReason.CANCELED));

        recording(1, () -> ledger.place(1, "b2", 1, Side.BUY, 100, 1));
        recording(1, () -> ledger.onRefused(new OrderKey(1, "b2")));
        recording(1, () -> ledger.place(1, "b3", 1, Side.BUY, 100, 1));
        recording(1, () -> ledger.onEngineRejected(new OrderKey(1, "b3"), 77));

        recording(1, () -> ledger.applyEngineEvent(
                event(EngineEventKind.CANCELLED, 3, 42, 0, 2, 0, Side.SELL, 100L, 6, "s1")));
        recording(1, () -> ledger.applyEngineEvent(
                event(EngineEventKind.RESTED, 4, 0, 0, 0, 0, Side.BUY, 100L, 0, "")));
        ledger.place(1, "b4", 1, Side.BUY, 100, 1);
        recording(1, () -> ledger.applyEngineEvent(
                event(EngineEventKind.REJECTED, 5, 78, 0, 1, 0, Side.BUY, 100L, 1, "b4")));
    }

    @Test
    void givenInputsThatChangeNothing_whenApplied_thenNothingIsRecorded() {
        openBoth();
        long before = recorded.size();

        recording(0, () -> assertInstanceOf(PlaceResult.Rejected.class,
                ledger.place(1, "big", 1, Side.BUY, 100, 1_000_000))); // insufficient cash
        recording(0, () -> assertInstanceOf(PlaceResult.Rejected.class,
                ledger.place(77, "nobody", 1, Side.BUY, 100, 1))); // unknown account
        recording(0, () -> assertInstanceOf(PlaceResult.Duplicate.class,
                ledger.place(1, "b1", 1, Side.BUY, 100, 10)));

        recording(1, () -> ledger.applyEngineEvent(
                event(EngineEventKind.TRADE, 1, 42, 41, 1, 2, Side.SELL, 100L, 10, "")));
        recording(0, () -> ledger.applyEngineEvent(
                event(EngineEventKind.TRADE, 1, 42, 41, 1, 2, Side.SELL, 100L, 10, ""))); // DUPLICATE

        recording(0, () -> assertEquals(CancelDecision.NOT_FOUND, ledger.requestCancel(1, new EngineRef(1, 999))));
        recording(0, () -> assertEquals(CancelDecision.CLOSED, ledger.requestCancel(1, BUY_REF)));
        recording(0, () -> assertEquals(CancelDecision.NOT_FOUND, ledger.requestCancel(2, BUY_REF)));

        recording(0, () -> ledger.onAccepted(BUY, 41)); // DONE now
        recording(0, () -> ledger.onRefused(new OrderKey(1, "nobody")));
        recording(0, () -> ledger.onNoAnswer(BUY));
        recording(0, () -> ledger.onCancelRefused(BUY_REF));
        assertEquals(before + 1, recorded.size());
    }

    @Test
    void givenAnOpenOrder_whenAcceptedAgain_thenNothingIsRecorded() {
        openBoth();
        recording(0, () -> ledger.onAccepted(BUY, 41));
        recording(0, () -> ledger.onCancelRefused(BUY_REF)); // no cancel pending
    }

    @Test
    void givenTransfers_whenApplied_thenExactlyOneTransferCommandIsRecordedAndRefusalsRecordNothing() {
        openBuyerAndSeller();
        recorded.clear();

        List<LedgerCommand> deposit = recording(1, () -> ledger.deposit(1, "t1", 500, 99));
        assertEquals(new LedgerCommand.Transfer(1, "t1", true, 500, 99), deposit.get(0));
        List<LedgerCommand> created = recording(1, () -> ledger.deposit(7, "t1", 500, 99)); // creates the account
        assertInstanceOf(LedgerCommand.Transfer.class, created.get(0));
        recording(1, () -> ledger.withdraw(1, "w1", 100, 100));

        recording(0, () -> ledger.deposit(1, "t1", 500, 99)); // Duplicate
        recording(0, () -> ledger.withdraw(1, "big", 1_000_000_000, 100)); // InsufficientCash
        recording(0, () -> ledger.withdraw(88, "w9", 1, 100)); // UnknownAccount
        assertFalse(ledger.account(88).isPresent());
    }

    @Test
    void givenACancelPending_whenOnFillFinishesTheOrder_thenCancelPendingIsCleared() {
        openBuyerAndSeller();
        ledger.place(1, "b1", 1, Side.BUY, 100, 5);
        ledger.onAccepted(BUY, 41);
        assertEquals(CancelDecision.SEND, ledger.requestCancel(1, BUY_REF));
        assertEquals(true, ledger.order(BUY).orElseThrow().cancelPending());

        ledger.onFill(BUY_REF, 100, 5);

        assertEquals(OrderStage.DONE, ledger.order(BUY).orElseThrow().stage());
        assertFalse(ledger.order(BUY).orElseThrow().cancelPending());
        assertDoesNotThrow(ledger::checkInvariants);
    }
}
