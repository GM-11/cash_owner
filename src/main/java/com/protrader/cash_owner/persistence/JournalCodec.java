package com.protrader.cash_owner.persistence;

import tools.jackson.databind.json.JsonMapper;

/** JSON in and out for journal records and snapshot files. One shared mapper (it is thread safe). */
final class JournalCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JournalCodec() {
    }

    /** What a journal record's payload holds. */
    record Entry(long n, LedgerCommand cmd) {
    }

    /** What a snapshot file holds. */
    record SnapshotFile(long entryNo, com.protrader.cash_owner.models.state.LedgerState state) {
    }

    static byte[] encode(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    static Entry decodeEntry(byte[] payload) {
        return MAPPER.readValue(payload, Entry.class);
    }

    static SnapshotFile decodeSnapshot(byte[] bytes) {
        return MAPPER.readValue(bytes, SnapshotFile.class);
    }
}
