package com.protrader.cash_owner.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.protrader.cash_owner.services.Ledger;

class LedgerStoreRecoveryTest {

    private static final int STEPS = 5000;
    private static final int CRASH_POINTS = 20;

    @TempDir
    Path temp;

    private Path liveDir;

    private static Path copyDir(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var files = Files.list(from)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
                Files.copy(file, to.resolve(file.getFileName()));
        }
        return to;
    }

    /** What recovery must give: the live state, with the SENT orders marked in doubt. */
    private static Object expectedAfterCrash(Ledger live) {
        Ledger expected = Ledger.fromState(live.exportState());
        for (var order : expected.sentOrders())
            expected.onNoAnswer(order.key());
        return expected.exportState();
    }

    private Ledger recoverCopy(Path dir, String name, int snapshotEvery) throws IOException {
        try (LedgerStore store = new LedgerStore(copyDir(dir, temp.resolve(name)), snapshotEvery, false)) {
            Ledger ledger = store.recover().ledger();
            ledger.checkInvariants();
            return ledger;
        }
    }

    private void assertCrashRecovers(Ledger live, int snapshotEvery, String name) throws IOException {
        assertEquals(expectedAfterCrash(live), recoverCopy(liveDir, name, snapshotEvery).exportState(),
                "state recovered at " + name);
    }

    private record Run(LedgerStore store, Ledger ledger) {
    }

    /** Random run with crash checks; returns the still-open store and live ledger. */
    private Run randomRun(int snapshotEvery) throws IOException {
        liveDir = temp.resolve("live");
        LedgerStore store = new LedgerStore(liveDir, snapshotEvery, false);
        Ledger ledger = store.recover().ledger();
        RandomLedgerDriver driver = new RandomLedgerDriver(42, ledger);
        Set<Integer> crashAt = new TreeSet<>();
        Random pick = new Random(7);
        while (crashAt.size() < CRASH_POINTS)
            crashAt.add(pick.nextInt(STEPS));
        for (int i = 0; i < STEPS; i++) {
            driver.step();
            if (crashAt.contains(i))
                assertCrashRecovers(ledger, snapshotEvery, "crash-" + i);
        }
        ledger.checkInvariants();
        assertCrashRecovers(ledger, snapshotEvery, "crash-end");
        return new Run(store, ledger);
    }

    @Test
    void givenARandomRunWithoutSnapshots_whenCrashedAtRandomPoints_thenRecoveryMatchesTheLiveLedger()
            throws Exception {
        try (LedgerStore store = randomRun(1_000_000).store()) {
            assertTrue(JournalFiles.listSnapshots(liveDir).isEmpty());
        }
    }

    @Test
    void givenARandomRunWithSnapshotsEvery100_whenCrashedAtRandomPoints_thenRecoveryMatchesAndCleanupKeepsWhatIsNeeded()
            throws Exception {
        Run run = randomRun(100);
        try (LedgerStore store = run.store()) {
            List<JournalFiles.SnapshotName> snapshots = JournalFiles.listSnapshots(liveDir);
            assertEquals(2, snapshots.size());
            long older = snapshots.get(1).entryNo();
            List<JournalFiles.Segment> segments = JournalFiles.listSegments(liveDir);
            assertTrue(segments.get(0).firstEntryNo() <= older + 1,
                    "the oldest kept segment must cover the entries after the older snapshot");

            // without the newest snapshot, the older one plus the kept segments must give the same state
            Path copy = copyDir(liveDir, temp.resolve("older-only"));
            Files.delete(copy.resolve(JournalFiles.snapshotName(snapshots.get(0).entryNo())));
            try (LedgerStore recovered = new LedgerStore(copy, 100, false)) {
                LedgerStore.Recovery recovery = recovered.recover();
                assertEquals(older, recovery.snapshotEntryNo());
                assertEquals(expectedAfterCrash(run.ledger()), recovery.ledger().exportState());
            }
        }
    }

    @Test
    void givenTheNewestSnapshotIsUnreadable_whenRecovered_thenThePreviousOneAndALongerTailGiveTheSameState()
            throws Exception {
        liveDir = temp.resolve("live");
        try (LedgerStore store = new LedgerStore(liveDir, 100, false)) {
            Ledger ledger = store.recover().ledger();
            RandomLedgerDriver driver = new RandomLedgerDriver(5, ledger);
            for (int i = 0; i < 700; i++)
                driver.step();
            List<JournalFiles.SnapshotName> snapshots = JournalFiles.listSnapshots(liveDir);
            Path copy = copyDir(liveDir, temp.resolve("broken-newest"));
            Files.writeString(copy.resolve(JournalFiles.snapshotName(snapshots.get(0).entryNo())), "not json");
            try (LedgerStore recovered = new LedgerStore(copy, 100, false)) {
                LedgerStore.Recovery recovery = recovered.recover();
                assertEquals(snapshots.get(1).entryNo(), recovery.snapshotEntryNo());
                assertTrue(recovery.replayedRecords() >= snapshots.get(0).entryNo() - snapshots.get(1).entryNo());
                assertEquals(expectedAfterCrash(ledger), recovery.ledger().exportState());
            }
        }
    }

    @Test
    void givenASnapshotWriteFails_whenRecording_thenTheCommandReturnsTheJournalHasItAndALaterRecordRetries()
            throws Exception {
        liveDir = temp.resolve("live");
        try (LedgerStore store = new LedgerStore(liveDir, 3, false)) {
            Ledger ledger = store.recover().ledger();
            // a directory where the snapshot's temp file must go makes the snapshot write fail
            Files.createDirectory(liveDir.resolve(JournalFiles.snapshotName(3) + ".tmp"));
            ledger.openAccount(1, 10, Map.of());
            ledger.openAccount(2, 10, Map.of());
            assertDoesNotThrow(() -> ledger.openAccount(3, 10, Map.of()));
            assertTrue(JournalFiles.listSnapshots(liveDir).isEmpty());

            Path copy = copyDir(liveDir, temp.resolve("after-failure"));
            try (LedgerStore recovered = new LedgerStore(copy, 3, false)) {
                LedgerStore.Recovery recovery = recovered.recover();
                assertEquals(0, recovery.snapshotEntryNo());
                assertEquals(3, recovery.replayedRecords());
                assertEquals(3, recovery.ledger().accountCount());
            }

            Files.delete(liveDir.resolve(JournalFiles.snapshotName(3) + ".tmp"));
            ledger.openAccount(4, 10, Map.of());
            assertTrue(Files.exists(liveDir.resolve(JournalFiles.snapshotName(4))), "next record retried the snapshot");
        }
    }

    private Path journalWithAccounts(int snapshotEvery, int accounts) throws IOException {
        liveDir = temp.resolve("live");
        try (LedgerStore store = new LedgerStore(liveDir, snapshotEvery, false)) {
            Ledger ledger = store.recover().ledger();
            for (int i = 1; i <= accounts; i++)
                ledger.openAccount(i, 10, Map.of());
        }
        return liveDir;
    }

    private static long recordLength(byte[] file, long position) {
        return JournalWriter.HEADER_BYTES + ByteBuffer.wrap(file).getInt((int) position);
    }

    private void assertRefused(Path dir, Path file, long position) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new LedgerStore(dir, 1_000_000, false).recover());
        assertTrue(e.getMessage().contains(file.toString()), e.getMessage());
        assertTrue(e.getMessage().contains("at byte " + position), e.getMessage());
    }

    @Test
    void givenACrcBrokenRecordInTheMiddleOfTheLastSegment_whenRecovered_thenItIsRefusedWithFileAndPosition()
            throws Exception {
        Path dir = journalWithAccounts(1_000_000, 3);
        Path file = JournalFiles.listSegments(dir).get(0).path();
        byte[] bytes = Files.readAllBytes(file);
        long second = recordLength(bytes, 0);
        bytes[(int) second + JournalWriter.HEADER_BYTES] ^= 1;
        Files.write(file, bytes);

        assertRefused(dir, file, second);
    }

    @Test
    void givenABadRecordInANonLastSegment_whenRecovered_thenItIsRefusedWithFileAndPosition() throws Exception {
        // snapshots at 2 and 4: segments journal-3 (entries 3-4) and journal-5 remain
        Path dir = journalWithAccounts(2, 5);
        List<JournalFiles.Segment> segments = JournalFiles.listSegments(dir);
        assertEquals(2, segments.size());
        Path file = segments.get(0).path();
        byte[] bytes = Files.readAllBytes(file);
        long second = recordLength(bytes, 0);

        // a flipped byte in the first record
        byte[] flipped = bytes.clone();
        flipped[JournalWriter.HEADER_BYTES] ^= 1;
        Files.write(file, flipped);
        assertRefused(dir, file, 0);

        // a torn last record is only allowed in the last segment
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertRefused(dir, file, second);
    }

    @Test
    void givenACrashBetweenTheSnapshotAndTheNewSegment_whenRecovered_thenANewSegmentIsOpenedAndCleanupKeepsWhatIsNeeded()
            throws Exception {
        liveDir = temp.resolve("live");
        try (LedgerStore store = new LedgerStore(liveDir, 3, false)) {
            Ledger ledger = store.recover().ledger();
            for (int i = 1; i <= 3; i++)
                ledger.openAccount(i, 10, Map.of());
        }
        Path newSegment = liveDir.resolve(JournalFiles.segmentName(4));
        assertTrue(Files.exists(newSegment));
        Files.delete(newSegment); // the crash: snapshot 3 is there, journal-4 is not

        try (LedgerStore store = new LedgerStore(liveDir, 3, false)) {
            LedgerStore.Recovery recovery = store.recover();
            assertEquals(3, recovery.snapshotEntryNo());
            assertTrue(Files.exists(newSegment), "recovery opened journal-4 instead of appending to journal-1");
            Ledger ledger = recovery.ledger();
            for (int i = 4; i <= 6; i++)
                ledger.openAccount(i, 10, Map.of());
        }

        List<JournalFiles.SnapshotName> snapshots = JournalFiles.listSnapshots(liveDir);
        assertEquals(List.of(6L, 3L), snapshots.stream().map(JournalFiles.SnapshotName::entryNo).toList());
        assertTrue(Files.exists(newSegment), "entries 4-6 are still needed by snapshot 3");
        assertFalse(Files.exists(liveDir.resolve(JournalFiles.segmentName(1))));

        Path copy = copyDir(liveDir, temp.resolve("no-newest"));
        Files.delete(copy.resolve(JournalFiles.snapshotName(6)));
        try (LedgerStore store = new LedgerStore(copy, 3, false)) {
            LedgerStore.Recovery recovery = store.recover();
            assertEquals(3, recovery.snapshotEntryNo());
            assertEquals(6, recovery.ledger().accountCount());
        }
    }
}
