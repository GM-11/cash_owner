package com.protrader.cash_owner.persistence;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Appends records to one journal file: {@code [int32 length][int32 CRC32 of payload][payload]}. Every append is
 * fsynced before it returns (unless {@code fsync} is off, which only tests do).
 */
final class JournalWriter implements Closeable {

    static final int HEADER_BYTES = 8;
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private final FileChannel channel;
    private final boolean fsync;

    private JournalWriter(FileChannel channel, boolean fsync) {
        this.channel = channel;
        this.fsync = fsync;
    }

    /** Opens (creating if needed) and positions at the end of the file. */
    static JournalWriter open(Path file, boolean fsync) throws IOException {
        boolean created = !Files.exists(file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            channel.position(channel.size());
            if (created && fsync) {
                JournalFiles.fsyncDir(file.toAbsolutePath().getParent());
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return new JournalWriter(channel, fsync);
    }

    /** One framed record, as it is written to disk. */
    static byte[] frame(byte[] payload) {
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("bad journal payload size: " + payload.length);
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        buffer.putInt(payload.length).putInt((int) crc.getValue()).put(payload);
        return buffer.array();
    }

    void append(byte[] payload) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(frame(payload));
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        if (fsync) {
            channel.force(false);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
