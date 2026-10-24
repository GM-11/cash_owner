# Cash owner

## Ledger persistence

The cash owner persists all successful Ledger inputs in an append-only, CRC32-checked journal and periodically writes snapshots. Persistence is configured in `application.yaml` under `cash-owner.persistence`; it is enabled by default, uses `./data`, and snapshots every 10,000 changed Ledger inputs. Clean stop writes a final snapshot. Delete the data directory only when deliberately resetting the associated engine/Kafka state.

The persistence package contains the recovery format and its operational limitations. In particular:

- The engine and sequencer keep no state across restarts yet (the sequencer journal is [later]). Persistence makes a cash-owner-only restart safe; restarting engine/sequencer still makes the books disagree.
- Dev reset rule: whenever Kafka is wiped (`compose down -v`) or the engine is restarted fresh, delete `cash_owner/data` too. Otherwise `lastApplied` is ahead and every new event looks like a duplicate.
- IN_DOUBT orders after a restart stay locked until their ACCEPTED/REJECTED event arrives; the resend sweep for IN_DOUBT orders is [later].
- fsync per record; group commit and copy-on-write snapshots are [later].

Set `cash-owner.persistence.enabled: false` for in-memory operation (the unit tests do this).
