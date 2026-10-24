package com.protrader.cash_owner.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.protrader.cash_owner.services.Ledger;

/**
 * Owner-thread journal and snapshot store. Recovery is called synchronously
 * before the owner accepts jobs.
 */
public final class LedgerStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LedgerStore.class);

    public record Recovery(Ledger ledger, long snapshotEntryNo, long replayedRecords, boolean empty) {
    }

    private final Path dir;
    private final int snapshotEvery;
    private final boolean fsync;
    private JournalWriter writer;
    private long entryNo;
    private int sinceSnapshot;
    private long previousSnapshot;

    public LedgerStore(Path dir, int snapshotEvery) {
        this(dir, snapshotEvery, true);
    }

    LedgerStore(Path dir, int snapshotEvery, boolean fsync) {
        if (snapshotEvery < 1)
            throw new IllegalArgumentException("snapshotEvery must be >= 1");
        this.dir = dir.toAbsolutePath();
        this.snapshotEvery = snapshotEvery;
        this.fsync = fsync;
    }

    public Recovery recover() throws IOException {
        Files.createDirectories(dir);
        List<JournalFiles.SnapshotName> snapshots = JournalFiles.listSnapshots(dir);
        Ledger ledger = new Ledger();
        long snapshotNo = 0;
        for (JournalFiles.SnapshotName name : snapshots) {
            try {
                JournalCodec.SnapshotFile snapshot = SnapshotFiles.read(name);
                ledger = Ledger.fromState(snapshot.state());
                snapshotNo = snapshot.entryNo();
                break;
            } catch (RuntimeException | IOException e) {
                log.warn("ignoring unreadable snapshot {}: {}", name.path(), e.toString());
            }
        }
        final Ledger replayLedger = ledger;
        List<JournalFiles.Segment> segments = JournalFiles.listSegments(dir);
        boolean hasJournalRecord = false;
        long expected = snapshotNo + 1;
        long replayed = 0;
        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            JournalFiles.Segment segment = segments.get(segmentIndex);
            if (segment.firstEntryNo() > expected) {
                throw new IllegalStateException("journal " + segment.path() + " at byte 0: entry gap; expected "
                        + expected + " but segment starts at " + segment.firstEntryNo());
            }
            final long firstExpected = expected;
            final long[] nextExpected = { expected };
            final long[] count = { 0 };
            final boolean[] found = { false };
            boolean lastSegment = segmentIndex == segments.size() - 1;
            JournalReader.Scan scan = JournalReader.scan(segment.path(), segment.firstEntryNo(), lastSegment,
                    (entry, position) -> {
                        if (entry.n() < firstExpected)
                            return;
                        if (entry.n() != nextExpected[0]) {
                            throw new IllegalStateException(JournalReader.describe(segment.path(), position,
                                    "expected entry " + nextExpected[0] + " but found " + entry.n()));
                        }
                        entry.cmd().applyTo(replayLedger);
                        found[0] = true;
                        count[0]++;
                        nextExpected[0]++;
                    });
            expected = nextExpected[0];
            replayed += count[0];
            hasJournalRecord |= found[0];
            if (scan.tornTail()) {
                log.warn("truncating torn journal tail {} at byte {}: {}", segment.path(), scan.validBytes(),
                        scan.tornReason());
                try (FileChannel channel = FileChannel.open(segment.path(), StandardOpenOption.WRITE)) {
                    channel.truncate(scan.validBytes());
                    if (fsync)
                        channel.force(true);
                }
            }
        }
        entryNo = expected - 1;
        boolean empty = snapshotNo == 0 && !hasJournalRecord;
        // Until startup has resolved SENT orders, no callbacks can append to the
        // journal.
        ledger.checkInvariants();
        if (!segments.isEmpty()) {
            writer = JournalWriter.open(segments.get(segments.size() - 1).path(), fsync);
        } else {
            writer = JournalWriter.open(dir.resolve(JournalFiles.segmentName(entryNo + 1)), fsync);
        }
        previousSnapshot = snapshotNo;
        sinceSnapshot = (int) (entryNo - snapshotNo);
        final Ledger recoveredLedger = ledger;
        ledger.setRecorder(command -> record(command, recoveredLedger));
        for (var order : ledger.sentOrders())
            ledger.onNoAnswer(order.key());
        log.info("ledger recovered: snapshot {}, replayed {} records, {} accounts, {} open orders, {} in doubt",
                snapshotNo, replayed, ledger.accountCount(), ledger.openOrdersCount(), ledger.inDoubt().size());
        return new Recovery(ledger, snapshotNo, replayed, empty);
    }

    /** Called by Ledger's recorder, only from the owner thread. */
    public void record(LedgerCommand command, Ledger ledger) {
        if (writer == null)
            throw new IllegalStateException("ledger store is not recovered");
        long next = entryNo + 1;
        try {
            writer.append(JournalCodec.encode(new JournalCodec.Entry(next, command)));
        } catch (IOException | RuntimeException e) {
            throw new JournalFailedException("could not fsync ledger journal entry " + next, e);
        }
        entryNo = next;
        sinceSnapshot++;
        if (sinceSnapshot >= snapshotEvery)
            snapshot(ledger);
    }

    public boolean isEmpty() {
        return entryNo == 0 && previousSnapshot == 0;
    }

    public void snapshot(Ledger ledger) {
        if (entryNo == 0)
            return;
        try {
            SnapshotFiles.write(dir, entryNo, ledger.exportState(), fsync);
            if (writer != null)
                writer.close();
            writer = JournalWriter.open(dir.resolve(JournalFiles.segmentName(entryNo + 1)), fsync);
            cleanup(entryNo);
            previousSnapshot = entryNo;
            sinceSnapshot = 0;
        } catch (IOException e) {
            throw new JournalFailedException("could not write ledger snapshot at entry " + entryNo, e);
        }
    }

    private void cleanup(long newestSnapshot) throws IOException {
        List<JournalFiles.SnapshotName> snapshots = JournalFiles.listSnapshots(dir);
        long keepPrevious = snapshots.stream().mapToLong(JournalFiles.SnapshotName::entryNo)
                .filter(number -> number < newestSnapshot).max().orElse(0);
        for (JournalFiles.SnapshotName snapshot : snapshots) {
            if (snapshot.entryNo() < keepPrevious)
                Files.deleteIfExists(snapshot.path());
        }
        for (JournalFiles.Segment segment : JournalFiles.listSegments(dir)) {
            if (segment.firstEntryNo() <= keepPrevious)
                Files.deleteIfExists(segment.path());
        }
        if (fsync)
            JournalFiles.fsyncDir(dir);
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
