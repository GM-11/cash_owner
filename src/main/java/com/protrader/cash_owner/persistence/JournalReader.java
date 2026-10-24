package com.protrader.cash_owner.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/** Reads one journal file, checking every record. */
final class JournalReader {

    private JournalReader() {
    }

    /** Gets every good record, with the byte position it starts at. */
    @FunctionalInterface
    interface EntryConsumer {
        void accept(JournalCodec.Entry entry, long position);
    }

    /**
     * @param validBytes where the good part of the file ends
     * @param tornTail   a short or CRC-failing record was found at the very end (only allowed in the last segment)
     * @param lastEntryNo the last good entry's number ({@code firstEntryNo - 1} when the file has none)
     */
    record Scan(long validBytes, long fileSize, boolean tornTail, String tornReason, long lastEntryNo) {
    }

    /**
     * Reads {@code file} from its start. Entry numbers must be firstEntryNo, +1, +1 ... Anything bad throws
     * IllegalStateException naming the file and the position, except a torn tail in the last segment, which is
     * reported in the result (the caller truncates the file at {@code validBytes}).
     */
    static Scan scan(Path file, long firstEntryNo, boolean lastSegment, EntryConsumer consumer) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long position = 0;
        long expected = firstEntryNo;

        while (position < bytes.length) {
            long remaining = bytes.length - position;
            if (remaining < JournalWriter.HEADER_BYTES) {
                return torn(file, position, bytes.length, expected, lastSegment,
                        "only " + remaining + " bytes left for a record header");
            }
            int length = buffer.getInt((int) position);
            int storedCrc = buffer.getInt((int) position + 4);
            if (length <= 0 || length > JournalWriter.MAX_PAYLOAD_BYTES) {
                if (allZero(bytes, (int) position)) {
                    return torn(file, position, bytes.length, expected, lastSegment, "zero-filled tail");
                }
                throw corrupt(file, position, "bad record length " + length);
            }
            long end = position + JournalWriter.HEADER_BYTES + length;
            if (end > bytes.length) {
                return torn(file, position, bytes.length, expected, lastSegment,
                        "record needs " + length + " payload bytes but only "
                                + (bytes.length - position - JournalWriter.HEADER_BYTES) + " are left");
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, (int) position + JournalWriter.HEADER_BYTES, length);
            if ((int) crc.getValue() != storedCrc) {
                if (end == bytes.length) {
                    return torn(file, position, bytes.length, expected, lastSegment, "CRC mismatch in the last record");
                }
                throw corrupt(file, position, "CRC mismatch (record is not the last one in the file)");
            }

            byte[] payload = new byte[length];
            System.arraycopy(bytes, (int) position + JournalWriter.HEADER_BYTES, payload, 0, length);
            JournalCodec.Entry entry;
            try {
                entry = JournalCodec.decodeEntry(payload);
            } catch (RuntimeException e) {
                throw new IllegalStateException(describe(file, position, "payload is not a valid entry: " + e), e);
            }
            if (entry.n() != expected || entry.cmd() == null) {
                throw corrupt(file, position, "expected entry " + expected + " but found " + entry.n()
                        + (entry.cmd() == null ? " (no command)" : ""));
            }
            consumer.accept(entry, position);
            expected++;
            position = end;
        }
        return new Scan(position, bytes.length, false, null, expected - 1);
    }

    private static Scan torn(Path file, long position, long fileSize, long expected, boolean lastSegment,
            String reason) {
        if (!lastSegment) {
            throw corrupt(file, position, reason + " (not the last journal file)");
        }
        return new Scan(position, fileSize, true, reason, expected - 1);
    }

    private static boolean allZero(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static IllegalStateException corrupt(Path file, long position, String what) {
        return new IllegalStateException(describe(file, position, what));
    }

    static String describe(Path file, long position, String what) {
        return "journal " + file + " at byte " + position + ": " + what;
    }
}
