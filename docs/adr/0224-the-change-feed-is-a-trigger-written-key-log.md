# ADR-0224 — The exchange change feed is a trigger-written key log, attributed per transaction

- **Status:** Accepted — owner decision 2026-09-27 (attribution by a transaction variable).
- **Date:** 2026-09-27
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-013`, `-014`,
  `-022`) · [ADR-0218](0218-exchange-sync-semantics.md) (the sync semantics this implements) ·
  [ADR-0003](0003-inventory-append-only-group-on-read.md)

## Context

ADR-0218 decided that each synced resource has a snapshot and a change feed **sequenced at the
database level**, so writes that bypass the services still appear, and that removals leave
tombstones saying who removed the entry: `web`, `app`, a `client` with its client id and
installation, or `system`.

Several write paths never pass a service: the default-blueprint grant, „delete all", the admin
purge, user deletion, the Lager's org re-stamp and the owner reassignment of an account merge. A
blueprint's `isDefault` is not stored at all; it follows from `default_blueprint`, so a change of
the default set changes every owner's entry without touching their row. A feed built in the
services would miss all of these. A database trigger sees them, but not who is writing.

## Decision

1. **Triggers write a key log.** Every synced table carries an `AFTER` row trigger that records
   `(member, resource, entity key)` in `exchange_change`, whose `seq` identity column is the feed's
   cursor. The log holds keys, not values: the feed reads the current state of each changed key
   and answers a tombstone when the entity is gone. A change of `default_blueprint` records the
   product for every member who owns it.
2. **The writer names itself per transaction.** A `JpaTransactionManager` subclass sets the
   transaction-local variable `basetool.change_source` at the start of every transaction that is not
   read-only, from the current authentication: `web` or `app` by the token's client, `client|<id>|
   <installation key>` for a request the ingest gateway relayed, otherwise `system`. The triggers
   read it; an absent or unknown value counts as `system`. It costs one statement per writing
   transaction.
3. **Retention is a horizon.** A nightly job deletes entries older than 90 days and records the
   highest deleted `seq`; a cursor below it answers `410 CURSOR_EXPIRED`.
4. **Deleting a member drops their log.** The foreign key cascades, and the trigger records nothing
   for a member who no longer exists, so a cascading delete cannot fail on the log.
5. **A test guards the set.** It fails when a synced table carries no exchange trigger.

## Consequences

- Every write path is in the feed by construction, including ones added later to a synced table.
- A new synced table needs its trigger and an entry in the guard test.
- A write outside any transaction, or in one the manager did not begin, counts as `system`.
- The log grows with every write to a synced table, whether or not a client is connected. Retention
  and the per-member index bound it.

## Alternatives considered

- **Record in the services.** Rejected: the bulk paths and the computed `isDefault` bypass them.
- **Triggers without attribution, filled in where the service knows.** Rejected by the owner: bulk
  paths would read `system` even when an admin triggered them in the web.
- **Logical decoding.** Rejected: it needs a replication slot and a consumer process for a volume
  one table can hold.
