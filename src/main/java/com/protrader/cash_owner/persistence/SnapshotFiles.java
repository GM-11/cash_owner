package com.protrader.cash_owner.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import com.protrader.cash_owner.models.state.LedgerState;

/** Reads and writes snapshot files: {@code {"entryNo": N, "state": ...}}. */
final class SnapshotFiles {

    private SnapshotFiles() {
    }

    /** temp file -> fsync -> atomic rename -> fsync the directory. A crash leaves at most a temp file. */
    static void write(Path dir, long entryNo, LedgerState state, boolean fsync) throws IOException {
        byte[] bytes = JournalCodec.encode(new JournalCodec.SnapshotFile(entryNo, state));
        Path finalFile = dir.resolve(JournalFiles.snapshotName(entryNo));
        Path tempFile = dir.resolve(JournalFiles.snapshotName(entryNo) + ".tmp");
        try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (fsync) {
                channel.force(true);
            }
        }
        Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        if (fsync) {
            JournalFiles.fsyncDir(dir);
        }
    }

    /** Throws when the file is unreadable or its content does not match its name. */
    static JournalCodec.SnapshotFile read(JournalFiles.SnapshotName name) throws IOException {
        JournalCodec.SnapshotFile snapshot = JournalCodec.decodeSnapshot(Files.readAllBytes(name.path()));
        if (snapshot.state() == null || snapshot.entryNo() != name.entryNo()) {
            throw new IllegalStateException("snapshot " + name.path() + " holds entry " + snapshot.entryNo()
                    + " or no state");
        }
        return snapshot;
    }
}
