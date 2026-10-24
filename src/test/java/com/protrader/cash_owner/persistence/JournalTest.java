package com.protrader.cash_owner.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import com.protrader.cash_owner.models.DoneReason;
import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.EngineRef;
import com.protrader.cash_owner.models.OrderKey;
import com.protrader.cash_owner.models.Side;

/**
 * Wire-format tests for the journal's commands, frame checksum, and strict
 * entry-number recovery.
 */
class JournalTest {
    @TempDir
    Path temp;
    private static final OrderKey KEY = new OrderKey(21, "b1");
    private static final EngineRef REF = new EngineRef(1, 4);

    @Test
    void givenEveryLedgerCommandType_whenEncodedAndDecoded_thenRoundTrips() {
        List<LedgerCommand> commands = List.of(
                new LedgerCommand.OpenAccount(21, 123_000, Map.of(1, 5L)),
                new LedgerCommand.Place(21, "b1", 1, Side.BUY, 10_000, 5),
                new LedgerCommand.Accepted(KEY, 4), new LedgerCommand.Refused(KEY),
                new LedgerCommand.NoAnswer(KEY), new LedgerCommand.Fill(REF, 9_900, 2),
                new LedgerCommand.RemainderEnded(REF, 3, DoneReason.CANCELED),
                new LedgerCommand.EngineRejected(KEY, 4),
                new LedgerCommand.EngineEventApplied(new EngineEvent(1, 1, EngineEventKind.RESTED, 10, 4, 0,
                        21, 0, Side.BUY, 10_000L, 5, "b1")),
                new LedgerCommand.RequestCancel(21, REF), new LedgerCommand.CancelRefused(REF));

        for (LedgerCommand command : commands) {
            JournalCodec.Entry before = new JournalCodec.Entry(1, command);
            JournalCodec.Entry after = JournalCodec.decodeEntry(JournalCodec.encode(before));
            assertEquals(before, after, "command type " + command.getClass().getSimpleName());
        }
    }

    @Test
    void givenAFramedRecord_whenItsPayloadChanges_thenCrcValidationRejectsIt() {
        byte[] payload = JournalCodec
                .encode(new JournalCodec.Entry(1, new LedgerCommand.OpenAccount(21, 123, Map.of())));
        byte[] framed = JournalWriter.frame(payload);
        framed[framed.length - 1] ^= 0x01;
        int storedCrc = java.nio.ByteBuffer.wrap(framed).getInt(4);
        CRC32 actual = new CRC32();
        actual.update(framed, JournalWriter.HEADER_BYTES, framed.length - JournalWriter.HEADER_BYTES);
        assertTrue((int) actual.getValue() != storedCrc);
        Path journal = temp.resolve(JournalFiles.segmentName(1));
        try {
            Files.write(journal, framed);
            JournalReader.Scan scan = JournalReader.scan(journal, 1, true, (entry, position) -> {
            });
            assertTrue(scan.tornTail(), "the CRC-failing last record is classified as a torn tail");
            assertEquals(0, scan.validBytes());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
