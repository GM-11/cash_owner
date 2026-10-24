package com.protrader.cash_owner.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.services.Ledger;

/** Snapshot state round trip; fields include account_seq, PnL, orders, engine refs, and event seqs. */
class SnapshotTest {
    @TempDir Path temp;

    @Test
    void givenACompleteLedger_whenExportedAndRestored_thenSnapshotStateIsEqual() {
        Ledger ledger = new Ledger();
        ledger.openAccount(21, 100_000, Map.of(1, 20L));
        ledger.place(21, "s1", 1, Side.SELL, 10_000, 5);
        ledger.onAccepted(new OrderKey(21, "s1"), 4);
        ledger.requestCancel(21, new com.protrader.cash_owner.models.EngineRef(1, 4));

        assertEquals(ledger.exportState(), Ledger.fromState(ledger.exportState()).exportState());
    }

    @Test
    void givenACrashLeftATempSnapshot_whenRecovered_thenOnlyFinalSnapshotsAreConsidered() throws Exception {
        Files.writeString(temp.resolve("snapshot-00000000000000000001.json.tmp"), "not JSON");
        LedgerStore store = new LedgerStore(temp, 100, false);

        LedgerStore.Recovery recovery = store.recover();

        assertTrue(recovery.empty());
        assertEquals(0, recovery.snapshotEntryNo());
        store.close();
    }
}
