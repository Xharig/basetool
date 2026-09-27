# ADR-0227 — An admin undoes one client for every member, in the background, member by member

- **Status:** Accepted — owner decisions 2026-09-27.
- **Date:** 2026-09-27
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-034`,
  `REQ-XCH-022`, `REQ-XCH-003`) · [ADR-0218](0218-exchange-sync-semantics.md) (journal and undo) ·
  [ADR-0217](0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md) (the
  registry and suspension) · [ADR-0224](0224-the-change-feed-is-a-trigger-written-key-log.md) (the
  change log the undo checks against)

## Context

ADR-0218 gave every member an undo of one client's writes, and the threat model accepted that the
undo was per member only: after a malicious or faulty client release, every affected member had to
find and undo the damage themselves. The second go-live security review (#2092) listed that as an
accepted risk, and the owner withdrew the acceptance: an admin must be able to undo one client's
writes for all members at once.

Such a run touches every member who used the client — potentially hundreds, each with up to
thousands of journal entries. The frontend waits five seconds for a backend answer, so the run
cannot be a request. And while the client keeps writing, anything undone can be overwritten again.

## Decision

1. **The run suspends the client first, through the registry.** Starting a bulk undo of an active
   client calls the registry's own suspension (`ExchangeRegistryService.suspendClient`), so the
   mirror and the gateway refuse the client before anything is undone, and the suspension is audited
   like an admin's. Re-activating stays a separate admin action (owner decision).
2. **It reuses the member's undo, unchanged in its semantics.** Each member is undone by the same
   code as the member's own undo (`ExchangeUndoService.undoWithinRun`): an entry changed afterwards is
   skipped as `CHANGED_AFTERWARDS`, a vanished one as `GONE`, Materialbörse offers are not restored,
   and the reach is the configured retention (owner decision). The scope can be narrowed to one
   installation and/or one resource (owner decision).
3. **It runs in the background, one member per transaction.** The request records the run and
   returns `202`; a single-thread executor works through the members under the starting admin's
   authentication, so every write, change-log entry and audit row names that admin. One transaction
   per member bounds the transaction to one member's journal and lets a failing member roll back
   alone; the run's counters are added with one atomic `UPDATE` per member, so the run row never
   takes an optimistic-lock conflict. A partial unique index allows one running run per client.
4. **Progress and leftovers are stored, by reference.** `exchange_bulk_undo_run` keeps the scope,
   the state and the counters; `exchange_bulk_undo_skip` keeps each entry left alone by its journal
   id and reason, and each failed member, so no entry name or other member text is copied. The admin
   page resolves the names when it shows a run. Both are purged with the journal after 90 days.
5. **A restart fails the run, a new run repairs.** A run still `RUNNING` at the next start is marked
   `FAILED`. Starting again is safe, because the undo only touches journal entries that are not undone
   yet.
6. **Members are told, once per run.** Each member whose data the run changed gets one notification
   (`EXCHANGE_BULK_UNDO_APPLIED`, owner decision) naming the client by its registry display name.
7. **It is audited and instrumented.** `EXCHANGE_BULK_UNDO_STARTED` / `_FINISHED` frame the run, one
   `EXCHANGE_CHANGES_UNDONE` per member carries the run id; the run is the `exchange_bulk_undo` job of
   `TaskMetrics`, and `ExchangeBulkUndoFailed` alerts on a failed run.

## Consequences

- The threat model no longer accepts "undo per member only"; a compromised or faulty release is
  answered by one admin action.
- A bulk undo is irreversible in the same way a member's undo is: nothing restores what it took
  back, except the member or the client writing it again.
- A run survives no restart; the admin restarts it. A queue that survives restarts was rejected as
  more machinery than a rare incident action needs.
- Running the members in parallel was rejected: one member at a time keeps the database load of an
  incident action predictable, and the members' undo does not need to be fast.
- Keeping the run's skipped entries with their names was rejected: names are member data (a ship
  name is free text), and the journal still holds them for as long as the run is kept.
