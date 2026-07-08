# 19. Transaction-free write atomicity via commit-point and self-healing

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** Ricardo
- **Refines:** ADR-014 (lifecycle/CRUD — write mechanics), ADR-016 (head-pointer model — activation atomicity)

## Context

ADR-016 removed the replica set: the head-pointer model makes activation a
single-document update, so standalone Mongo is sufficient and the service no
longer has multi-document transactions available. That is fine for activation,
which touches one document. It is not the whole write surface.

The write slice (S2, ADR-018) lands two operations that each touch **two**
documents:

- **Create** writes a head (`policy_heads`) *and* version 1 (`policy_versions`).
- **Append** (`PUT`) writes a new version (`policy_versions`) *and* bumps
  `revision` on the head (`policy_heads`).

Without a transaction, each operation has a failure window between its two
writes: a crash or dropped connection can leave a half-written state (a head with
no version 1; a bumped `revision` with no matching version). We need write
atomicity — "the operation either takes effect or is safely retryable, never
leaving a corrupt state" — using only the single-document guarantees Mongo gives
us on standalone.

Two facts about the existing persistence make this tractable and are load-bearing
for the decision below:

- `policy_heads.policyId` has a **unique** index, and `policy_versions.(policyId,
  version)` has a **unique** compound index. Both are created at startup
  (`PolicyLifecycleIndexes`) and already exist in `main`.
- The head is a mutable pointer; versions are immutable and append-only. The head
  can be re-derived/completed; a version, once written, is final.

## Decision

Atomicity is achieved per operation by designating a single **commit point** — one
document write whose success or failure, arbitrated by a unique index, decides the
outcome of the whole operation — and by making every other write in the operation
**idempotent** so a retry of a partially-applied operation completes it (self-heals)
rather than corrupting or double-applying it. No transactions, no compensation, no
background cleanup process.

### 1. Create (head-first)

The order is fixed: **head first, version-1 second.** This ordering is not
arbitrary; see Reasons.

1. **Upsert the head** on `{policyId}` with `activeVersion = null`, `revision = 0`,
   `resourceType`, and audit metadata via `$setOnInsert`. If the head exists this
   is a **no-op** (idempotent). A concurrent create may cause the upsert itself to
   raise a duplicate-key error on the unique `policyId` index; that is treated as
   "the head already exists, proceed" — not an error.
2. **Insert version 1.** This is the **commit point**, arbitrated by the unique
   `(policyId, version)` index:
   - Success → **201 Created**. The policy exists, inactive (`activeVersion = null`).
   - `(policyId, 1)` already exists → **409 `POLICY_ALREADY_EXISTS`**. The policy
     was already fully created.

Consequences of this shape:

- **Orphan head (crash after step 1, before step 2)** self-heals: the retry finds
  the head present (upsert no-op) and inserts version 1 (commit) → 201. The
  half-written state is completed, not detected-and-rejected.
- **Concurrent identical creates:** both upsert the head (one inserts, one no-ops
  or absorbs an E11000); both attempt version 1; the unique index lets exactly one
  win (201) and the other gets 409. No lost write, no double head, no content
  comparison, no sweeper.

**Duplicate detection is the version-1 insert, never a head-existence pre-check.**
A pre-check (`if headExists → 409`) would turn every orphan head into a permanent
409 — a `policyId` that can neither be created nor evaluated. The unique index on
version 1 is the only correct duplicate arbiter here.

### 2. Append (`PUT`, append-only)

1. **Compare-and-set on `revision`** — this is *both* the `If-Match` check and the
   **commit point / serialization point** in one write:
   `updateOne({policyId, revision: ifMatch}, {$inc: {revision: 1}})`.
   - `matchedCount == 1` → proceed.
   - `matchedCount == 0` → the precondition failed. Disambiguate on this failure
     path only: if a head exists for `policyId`, the `revision` was stale →
     **412 `PRECONDITION_FAILED`** (carrying `currentRevision`); if no head exists →
     **404 `POLICY_NOT_FOUND`**.
2. **Insert the new version** with `version = max(existing version) + 1`, read fresh
   *after* winning the CAS. Because the CAS serializes writers per `revision` value,
   only one writer is ever past the gate for a given revision, so `max+1` is
   collision-free in the normal path. The unique `(policyId, version)` index remains
   as a backstop against a double-append that bypasses the `If-Match` discipline.

Consequences of this shape:

- **Crash between the CAS and the version insert** leaves a benign **gap in the
  `revision` counter** (it advanced without a corresponding version). `revision` is
  opaque (an ETag token, not a version number), so gaps are harmless. The retry
  presents the now-stale `If-Match` → 412 → the client reloads and re-issues; no
  corruption, no lost version.
- Missing `If-Match` is rejected at the transport layer → **428
  `PRECONDITION_REQUIRED`** (ADR-018); the CAS is never run unconditionally.

### 3. Idempotency semantics (precise)

"Safe to retry" here means **no corruption + partials complete themselves**. It is
*not* textbook idempotent replay: retrying an already-completed create returns
**409** (not a replayed 201), and retrying an already-completed append returns
**412** (not a replayed 200). True replay-idempotency would require an
`Idempotency-Key` with a server-side result store; ADR-018 deferred that as YAGNI
for a human-driven PAP. This ADR is consistent with that: at-most-once *effect*,
with a clear "someone/you already did this, reload" signal on retry.

Self-healing is retry-scoped: an orphan head heals on the **next create attempt for
that id**. If no retry ever comes, the orphan head persists — invisible to reads and
`/evaluate` (both gated on an active version), harmless, and completed by the next
create for that id. It is not swept.

## Reasons

- **Transaction-free by construction** — respects ADR-016; no reason to reinstate
  the replica set for two local two-document writes.
- **One arbiter per operation.** A single unique-index-guarded write as the commit
  point makes corrupt intermediate states unrepresentable as *committed* state; the
  worst case is an incomplete-but-completable partial.
- **Head-first, not version-first, is load-bearing.** The head is the idempotent,
  re-completable part; the version is the immutable commit point. With head-first,
  an orphan is a head-without-version, which the retry *completes*. With
  version-first, an orphan would be a version-without-head: invisible to reads, and
  its retry hits the unique index → 409 with no head to create → a permanent
  tombstone. The order is therefore fixed, not an implementation preference.
- **Reuses indexes S1 already creates.** No new schema, no new infrastructure; the
  uniqueness guarantees are already in `main`.
- **Self-healing over a cleanup process.** Partials are completed by the natural
  retry path, so no scheduled reconciliation job is needed — fewer moving parts,
  boring over clever.

## Alternatives considered

- **Multi-document ACID transactions (head + version in one commit).** The most
  robust option. Impact: requires reinstating a Mongo replica set — exactly what
  ADR-016 removed — to protect two local writes. The cost of reverting that (infra,
  ops, the standalone-sufficient property of the product) is not justified.
  Rejected, not omitted.
- **Saga with compensation.** Explicit compensating actions for the second write.
  Impact: orchestration and rollback machinery for two local inserts.
  Over-engineering. Rejected.
- **Version-first ordering.** Insert the version as commit point, then upsert the
  head. Rejected: produces version-without-head orphans that are invisible to reads
  and un-retryable (409, no head), i.e. permanent tombstones. See Reasons.

### Better option not omitted (and its impact)

- **Background reconciliation sweeper for orphan heads** (the deferred option from
  ADR-016). Its real value is not correctness — the self-heal already makes orphans
  harmless — but **observability**: a sweeper (or even a counter/log on
  orphan-head-encountered during create) would reveal how often crashes leave
  partials, i.e. crash frequency during writes. Impact: a scheduled job (or at least
  a metric) and more moving parts, against "boring/stable over comprehensive."
  Deferred, not omitted: adopt a metric first if partials ever need to be observed,
  a full sweeper only if bounded cleanup becomes a real requirement.

## Consequences

- Create and append coexist with transient, self-completing partials; there is no
  window in which committed state is corrupt.
- `revision` may contain gaps after a crash between the append CAS and its version
  insert. This is accepted: `revision` is an opaque ETag token, not a contiguous
  counter, and clients must not infer version count from it.
- **The implementation is constrained, not free:** (a) create's duplicate arbiter is
  the version-1 insert, never a head pre-check; (b) create is head-first; (c) the
  head upsert must absorb a concurrent-create duplicate-key on `policyId` as
  success; (d) append's `matchedCount == 0` must disambiguate 412 vs 404 on the
  failure path. These are correctness requirements of this ADR, verified by the S2
  tests (idempotent-409, orphan-self-heal, stale-412, missing-404), not stylistic
  choices.
- The unique indexes on `policy_heads.policyId` and `policy_versions.(policyId,
  version)` are a hard prerequisite (already present, `PolicyLifecycleIndexes`).
- **Append precondition disambiguation has a benign race.** When the append CAS
  matches zero documents, the store re-reads the head to distinguish 412
  (head exists, revision stale) from 404 (no head). If a concurrent create inserts
  the head between the failed CAS and that re-read, the caller receives 412 instead
  of 404. This is accepted: no data is corrupted, the 412 carries the current
  revision, and the client's retry (with the fresh `If-Match`) wins the CAS
  normally. Making this boundary atomic would require a transaction for a
  self-correcting edge, against "stable over comprehensive." The 404/412 split is
  best-effort classification, not a serialization guarantee.

## Criteria to revisit

- A consumer requires true replay-idempotency (a retry returning the original
  success, not 409/412) → introduce `Idempotency-Key` with a keyed result store.
- A write operation grows to touch more than two documents, where no single write
  can serve as a sufficient commit point → reconsider transactions (and the replica
  set) for that operation.
- Write-time partials become frequent or operationally interesting → add an
  orphan-head metric first, and only then a reconciliation sweeper.

