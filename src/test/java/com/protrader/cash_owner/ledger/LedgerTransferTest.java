package com.protrader.cash_owner.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.models.TransferResult;
import com.protrader.cash_owner.models.views.AccountView;
import com.protrader.cash_owner.models.views.BalancesView;
import com.protrader.cash_owner.models.views.TransferView;
import com.protrader.cash_owner.models.state.LedgerState;
import com.protrader.cash_owner.services.Ledger;

class LedgerTransferTest {

    private static final long ALICE = 1;
    private static final long BOB = 2;
    private static final long TS = 1_700_000_000_000L;

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.openAccount(ALICE, 100_000, Map.of(1, 10L));
    }

    private AccountView account(long id) {
        return ledger.account(id).orElseThrow();
    }

    private AccountChange applied(TransferResult result) {
        ledger.checkInvariants();
        return assertInstanceOf(TransferResult.Applied.class, result).change();
    }

    @Test
    void givenAnExistingAccount_whenDeposited_thenFreeCashGrowsLockedStaysAndOneTransferChangeIsEmitted() {
        ledger.place(ALICE, "b1", 1, Side.BUY, 100, 200); // locks 20,000
        AccountView before = account(ALICE);

        AccountChange change = applied(ledger.deposit(ALICE, "t1", 5_000, TS));

        assertEquals(before.freeCashMinor() + 5_000, account(ALICE).freeCashMinor());
        assertEquals(before.lockedCashMinor(), account(ALICE).lockedCashMinor());
        assertEquals(before.accountSeq() + 1, change.accountSeq());
        assertEquals(AccountChangeKind.TRANSFER, change.kind());
        assertEquals(new BalancesView(account(ALICE).freeCashMinor(), 20_000, 0), change.balances());
        assertEquals(new TransferView("t1", true, 5_000), change.transfer());
        assertNull(change.order());
        assertNull(change.position());
        assertEquals(TS, change.ts());
    }

    @Test
    void givenAMissingAccount_whenDeposited_thenItIsCreatedWithSeqOne() {
        AccountChange change = applied(ledger.deposit(BOB, "t1", 700, TS));

        assertEquals(700, account(BOB).freeCashMinor());
        assertEquals(0, account(BOB).lockedCashMinor());
        assertTrue(account(BOB).holdings().isEmpty());
        assertEquals(1, account(BOB).accountSeq());
        assertEquals(1, change.accountSeq());
    }

    @Test
    void givenAnAppliedTransfer_whenTheSameIdComesAgain_thenDuplicateWithCurrentBalancesAndNothingChanges() {
        applied(ledger.deposit(ALICE, "t1", 5_000, TS));
        applied(ledger.deposit(ALICE, "t2", 1_000, TS));
        LedgerState before = ledger.exportState();

        TransferResult.Duplicate duplicate = assertInstanceOf(TransferResult.Duplicate.class,
                ledger.deposit(ALICE, "t1", 5_000, TS + 1));

        assertEquals(new BalancesView(106_000, 0, 0), duplicate.balances());
        assertEquals(2, duplicate.accountSeq());
        assertEquals(before, ledger.exportState());
    }

    @Test
    void givenAnAppliedTransfer_whenTheSameIdComesWithAnotherAmountOrKind_thenStillDuplicateAndNothingChanges() {
        applied(ledger.deposit(ALICE, "t1", 5_000, TS));
        LedgerState before = ledger.exportState();

        assertInstanceOf(TransferResult.Duplicate.class, ledger.deposit(ALICE, "t1", 9_999, TS));
        assertInstanceOf(TransferResult.Duplicate.class, ledger.withdraw(ALICE, "t1", 5_000, TS));

        assertEquals(before, ledger.exportState());
    }

    @Test
    void givenAnAppliedTransfer_whenTheSameIdIsUsedOnAnotherAccount_thenItIsApplied() {
        applied(ledger.deposit(ALICE, "t1", 5_000, TS));
        applied(ledger.deposit(BOB, "t1", 5_000, TS));

        assertEquals(5_000, account(BOB).freeCashMinor());
    }

    @Test
    void givenFreeCash_whenWithdrawnWithinIt_thenApplied() {
        AccountChange change = applied(ledger.withdraw(ALICE, "w1", 100_000, TS));

        assertEquals(0, account(ALICE).freeCashMinor());
        assertEquals(new TransferView("w1", false, 100_000), change.transfer());
        assertEquals(1, change.accountSeq());
    }

    @Test
    void givenSomeCashLocked_whenWithdrawingMoreThanFree_thenInsufficientCashWithShortfallAndNothingRemembered() {
        ledger.place(ALICE, "b1", 1, Side.BUY, 100, 600); // locks 60,000; free 40,000
        LedgerState before = ledger.exportState();

        TransferResult.InsufficientCash refused = assertInstanceOf(TransferResult.InsufficientCash.class,
                ledger.withdraw(ALICE, "w1", 70_000, TS));

        assertEquals(30_000, refused.shortfallMinor());
        assertEquals(new BalancesView(40_000, 60_000, 0), refused.balances());
        assertEquals(0, refused.accountSeq());
        assertEquals(before, ledger.exportState());
        ledger.checkInvariants();
    }

    @Test
    void givenARefusedWithdraw_whenCashArrivesAndTheSameIdIsTriedAgain_thenItIsApplied() {
        assertInstanceOf(TransferResult.InsufficientCash.class, ledger.withdraw(ALICE, "w1", 150_000, TS));
        applied(ledger.deposit(ALICE, "d1", 100_000, TS));

        applied(ledger.withdraw(ALICE, "w1", 150_000, TS));

        assertEquals(50_000, account(ALICE).freeCashMinor());
    }

    @Test
    void givenAMissingAccount_whenWithdrawn_thenUnknownAccountAndNothingIsCreatedOrRemembered() {
        assertInstanceOf(TransferResult.UnknownAccount.class, ledger.withdraw(BOB, "w1", 10, TS));

        assertTrue(ledger.account(BOB).isEmpty());
        applied(ledger.deposit(BOB, "w1", 10, TS)); // the id was not remembered
    }

    @Test
    void givenBadInput_whenTransferring_thenItThrowsAndNothingChanges() {
        LedgerState before = ledger.exportState();

        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(ALICE, "t1", 0, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(ALICE, "t1", -5, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.withdraw(ALICE, "t1", 0, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(ALICE, "", 5, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(ALICE, null, 5, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(ALICE, "has space", 5, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(ALICE, "x".repeat(49), 5, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(0, "t1", 5, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.withdraw(-1, "t1", 5, TS));
        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(ALICE, "t1", 5, -1));
        assertThrows(IllegalArgumentException.class, () -> ledger.deposit(ALICE, "t1", Long.MAX_VALUE, TS)); // overflow

        assertEquals(before, ledger.exportState());
    }

    @Test
    void givenTransfersAndOrderChangesInterleaved_whenApplied_thenAccountSeqCountsUpWithoutGaps() {
        ledger.place(ALICE, "b1", 1, Side.BUY, 100, 10);
        ledger.onAccepted(new com.protrader.cash_owner.models.OrderKey(ALICE, "b1"), 41);
        assertEquals(1, applied(ledger.deposit(ALICE, "t1", 100, TS)).accountSeq());
        var accepted = ledger.applyEngineEvent(new com.protrader.cash_owner.models.EngineEvent(1, 1,
                com.protrader.cash_owner.models.EngineEventKind.CANCELLED, 0, 41, 0, ALICE, 0, Side.BUY, 100L, 10,
                "b1"));
        assertEquals(2, accepted.changes().get(0).accountSeq());
        assertEquals(3, applied(ledger.withdraw(ALICE, "t2", 50, TS)).accountSeq());
        assertEquals(3, account(ALICE).accountSeq());
    }

    @Test
    void givenTransfers_whenStateIsExportedAndRestored_thenTheyAreStillRemembered() {
        applied(ledger.deposit(BOB, "t1", 70, TS));
        applied(ledger.withdraw(BOB, "t2", 20, TS));

        Ledger restored = Ledger.fromState(ledger.exportState());

        assertEquals(ledger.exportState(), restored.exportState());
        assertInstanceOf(TransferResult.Duplicate.class, restored.deposit(BOB, "t1", 70, TS));
        assertEquals(2, ledger.exportState().transfers().size());
    }

    @Test
    void givenATransferForAnUnknownAccount_whenStateIsRestored_thenItIsRefused() {
        LedgerState state = ledger.exportState();
        LedgerState bad = new LedgerState(state.accounts(), state.orders(), state.byEngineRef(), state.lastApplied(),
                java.util.List.of(new LedgerState.TransferState(77, "t1", true, 5, TS)));

        assertThrows(IllegalArgumentException.class, () -> Ledger.fromState(bad));
    }
}
