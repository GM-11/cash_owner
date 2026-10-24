package com.protrader.cash_owner.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.Side;
import com.protrader.cash_owner.services.Ledger;

class LedgerPersistenceTest {
    @TempDir
    Path temp;

    @Test
    void givenRecordedLedgerInputs_whenRecovered_thenStateMatchesAndSentOrdersBecomeInDoubt() throws Exception {
        LedgerStore store = new LedgerStore(temp, 100, false);
        LedgerStore.Recovery recovery = store.recover();
        Ledger ledger = recovery.ledger();
        ledger.openAccount(1, 100_000, Map.of(1, 10L));
        ledger.setRecorder(command -> store.record(command, ledger));
        ledger.place(1, "b1", 1, Side.BUY, 10_000, 5);
        ledger.onAccepted(new OrderKey(1, "b1"), 41);
        ledger.requestCancel(1, new EngineRef(1, 41));
        Ledger expected = Ledger.fromState(ledger.exportState());
        store.close();

        LedgerStore restartedStore = new LedgerStore(temp, 100, false);
        LedgerStore.Recovery restarted = restartedStore.recover();
        assertEquals(expected.exportState(), restarted.ledger().exportState());
        assertTrue(restarted.ledger().order(new OrderKey(1, "b1")).orElseThrow().cancelPending());
        restartedStore.close();
    }

    @Test
    void givenATornJournalTail_whenRecovered_thenTheTailIsTruncated() throws Exception {
        LedgerStore store = new LedgerStore(temp, 10_000, false);
        Ledger ledger = store.recover().ledger();
        ledger.setRecorder(command -> store.record(command, ledger));
        ledger.openAccount(1, 20, Map.of());
        store.close();
        Path journal;
        try (var files = Files.list(temp)) {
            journal = files.filter(p -> p.toString().endsWith(".log")).findFirst().orElseThrow();
        }
        long validSize = Files.size(journal);
        Files.write(journal, new byte[] { 0, 0, 0, 10, 1 }, java.nio.file.StandardOpenOption.APPEND);

        LedgerStore recoveredStore = new LedgerStore(temp, 10_000, false);
        LedgerStore.Recovery recovered = recoveredStore.recover();
        assertEquals(1, recovered.ledger().accountCount());
        assertEquals(validSize, Files.size(journal));
        recoveredStore.close();
    }

    @Test
    void givenSnapshotEveryInput_whenRecovered_thenSnapshotRoundTripsAndTempFilesAreIgnored() throws Exception {
        LedgerStore store = new LedgerStore(temp, 1, false);
        Ledger ledger = store.recover().ledger();
        ledger.setRecorder(command -> store.record(command, ledger));
        ledger.openAccount(1, 20, Map.of(1, 2L));
        assertTrue(Files.list(temp).anyMatch(path -> path.getFileName().toString().endsWith(".json")));
        Files.writeString(temp.resolve("snapshot-99999999999999999999.json.tmp"), "broken");
        Ledger expected = Ledger.fromState(ledger.exportState());
        store.close();

        LedgerStore next = new LedgerStore(temp, 1, false);
        Ledger recovered = next.recover().ledger();
        assertEquals(expected.exportState(), recovered.exportState());
        assertTrue(Files.exists(temp.resolve("snapshot-99999999999999999999.json.tmp")),
                "temporary snapshot files are ignored rather than treated as snapshots");
        next.close();
    }

    @Test
    void givenAnEntryNumberGap_whenRecovered_thenStartupIsRefused() throws Exception {
        LedgerStore store = new LedgerStore(temp, 100, false);
        Ledger ledger = store.recover().ledger();
        ledger.setRecorder(command -> store.record(command, ledger));
        ledger.openAccount(1, 20, Map.of());
        store.close();
        Path original;
        try (var files = Files.list(temp)) {
            original = files.filter(p -> p.toString().endsWith(".log")).findFirst().orElseThrow();
        }
        Files.move(original, temp.resolve("journal-00000000000000000002.log"));

        LedgerStore bad = new LedgerStore(temp, 100, false);
        assertThrows(IllegalStateException.class, bad::recover);
    }
}
