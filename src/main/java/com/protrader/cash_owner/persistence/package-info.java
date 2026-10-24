/**
 * Ledger persistence: an append-only journal of every Ledger input that changed state, plus periodic snapshots.
 *
 * <p>
 * Files in the data dir: {@code journal-<firstEntryNo, 20 digits>.log} and {@code snapshot-<entryNo, 20 digits>.json}.
 * A journal record is {@code [int32 length][int32 CRC32 of payload][payload]}, the payload is JSON
 * {@code {"n": entryNo, "cmd": {...}}}. Recovery = newest readable snapshot + replay of the journal after it.
 *
 * <p>
 * <b>Known limits</b> (read before relying on this):
 * <ul>
 * <li>The engine and sequencer keep no state across restarts yet (the sequencer journal is [later]). This makes a
 * cash-owner-only restart safe. Restarting the engine or the sequencer still makes the books disagree.</li>
 * <li>Dev reset rule: whenever Kafka is wiped ({@code compose down -v}) or the engine is restarted fresh, delete
 * {@code cash_owner/data} too. Otherwise lastApplied is ahead and every new event looks like a duplicate.</li>
 * <li>IN_DOUBT orders after a restart stay locked until their ACCEPTED/REJECTED event arrives; the resend sweep for
 * IN_DOUBT orders is [later].</li>
 * <li>fsync per record; group commit and copy-on-write snapshots are [later].</li>
 * </ul>
 */
package com.protrader.cash_owner.persistence;
