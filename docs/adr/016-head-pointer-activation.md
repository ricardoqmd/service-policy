# 016 - Head-pointer activation model (revises 014 §7)

- **Date:** 2026-07-06
- **Status:** Accepted
- **Deciders:** Ricardo Quintero
- **Refines:** 014 (Policy lifecycle and CRUD contract), §7 activation atomicity

## Context

ADR-014 §7 modeled activation with a mutable `active` boolean carried on each
version document. Flipping the active version therefore mutated **two** documents
(the outgoing active version → `false`, the incoming → `true`), which requires a
multi-document transaction to stay atomic, which in turn requires MongoDB to run
as a replica set (the oplog only exists on replica-set members). ADR-014 accepted
this and defined an ordered-writes fallback for deployments that cannot run one.

A later smoke run clarified that the replica-set requirement is unavoidable for
model T wherever `activate` runs against real Mongo, including production — it is
free only in local/test because Quarkus Dev Services provisions a replica set
automatically. That reframed the question: rather than *satisfy* the transaction
requirement everywhere, can the activation model be restructured so no
transaction is needed at all?

The domain supports this. The PDP evaluates by combining the active policy set
with deny-overrides; policies are independent aggregates that do not coordinate
their writes. Cross-document atomicity does not arise naturally here — and if a
requirement ever demanded coordinating the activation of one policy with the
mutation of another, that is workflow/orchestration (a business process, e.g.
Flowable) sitting *above* the PDP, not persistence-layer coordination *inside* it.

Note on current state at decision time: the R014 lifecycle (versioning,
`activate`/`deactivate`, inactive-on-create) is **not yet implemented** — `create`
still persists a single active document (R012 behavior). Neither model's
two-document operation exists in the codebase today, so this decision is made
before any activation code calcifies, not as a migration.

## Decision

Replace the mutable `active` flag with a **head-pointer** model:

- **`policy_versions`** — immutable, append-only version documents. Written once,
  never mutated. No `active` field.
- **`policy_heads`** — one document per `policyId`, carrying the activation
  pointer and a denormalized copy of the active content:
  `{ policyId, activeVersion, resourceType, activeContent, revision, audit }`.

Operations:
- **create (first write of a new `policyId`):** write the head first with
`activeVersion=null`, then write the first immutable version. **Idempotent:** a
retry detects an existing head / orphan version and completes the pair instead
of duplicating. See the orphan discussion under Consequences.
- **new version of an existing policy:** the head already exists; write the
immutable version. If it is to become active, `activate` updates the head. (So
"head-first" is the rule for the *first* write of a `policyId`, not for every
version write.)
- **activate(policyId, version):** single-document update on the head
(`activeVersion`, `activeContent` copied from the immutable version, `revision`
bumped). Atomic by MongoDB's single-document guarantee — **no transaction, no
replica set**, works on standalone Mongo.
- **deactivate:** single-document update on the head.
- **optimistic concurrency:** compare-and-set on `revision`
(`updateOne({_id, revision:r}, {$set:{…, revision:r+1}})`); the loser matches 0
documents → 409. No transaction needed.
- **evaluation read (hot path):** query `policy_heads` by `{resourceType}` — one
indexed query returning one document per active policy, matching the previous
read shape and latency.

The replica set is **no longer a requirement** of this repository. Deployments may
run standalone Mongo. (Running a replica set for other reasons, e.g. HA, remains
an independent operational choice.)

## Reasons

- **Structural invariant.** "At most one active version" becomes unrepresentable:
  activation is a single scalar in one document, not a flag replicated across N
  version docs. The illegal "two active" state cannot be written.
- **Zero-friction adoption.** A public/portfolio repo runs on plain
  `docker run mongo` (standalone) instead of requiring replica-set init.
- **True append-only.** Version documents are never mutated; all mutability is
  concentrated in the head.
- **Idiomatic MongoDB.** Aggregate = document; a mutable current-pointer over an
  immutable log is the standard document-store pattern. Multi-document
  transactions are the escape hatch this model no longer needs.

## Alternatives considered

- **Model T — keep the transactional flag (ADR-014 §7 as written).** Rejected for
  the public repo: it makes a replica set a hard requirement for a component that
  never needs cross-document atomicity, and enforces the ≤1-active invariant at
  runtime rather than structurally. It also carries *more* moving parts to
  maintain (replica-set operations + transactional `activate` + the ordered-writes
  fallback + tests for the two-active race), not fewer. For the internal
  deployment a replica set already exists, so T costs nothing extra *there* — the
  tilt to head-pointer is driven by public-repo adoption and structural
  correctness, not the internal case.
- **Model P naïve (head without denormalized content).** Rejected: evaluation
  reads by `resourceType`, so resolving active content through heads would require
  reading all heads and fetching each active version — worse than T on the hot
  path. Denormalizing `activeContent` onto the head restores the single-query read.

## Consequences

- (+) No replica set required; `activate`/`deactivate` atomic on standalone Mongo.
- (+) ≤1-active is structural; the tests for the "two active" race disappear.
- (+) Hot-path read unchanged (one indexed query on `policy_heads`).
- (−) **Controlled duplication.** `head.activeContent` duplicates the pointed
  version's content. A new invariant holds: `activeContent` must always equal the
  content of the version at `activeVersion`. It is safe because versions are
  immutable and the copy is written atomically inside `activate` — but this must be
  the *only* path that writes `activeContent`. Enforce with an ArchUnit rule: no
  method other than the activation path writes `head.activeContent`.
- (−) **create / first write is two documents** (head + first immutable version)
  and is **not** atomic. Primary handling is **head-first ordering + idempotent
  create**: the head is written first with `activeVersion=null`; a crash between
  the two writes leaves a head with no active version (invisible to reads —
  nothing to serve — and harmless), and the client's normal retry completes the
  pair without duplicating. A batch orphan-reconciliation job is **deferred**, not
  built now (see Criteria to revisit).
- Two collections instead of one.

## Criteria to revisit

- **Orphan accumulation.** Idempotent create clears the orphan on retry, but a
  create that crashes and is *never* retried leaves a permanent, harmless cold
  orphan. If cold-orphan accumulation ever becomes a storage or audit concern,
  introduce a batch reconciliation command. Do not build it preemptively.
- **Cross-aggregate atomicity.** If a requirement appears to coordinate state
  across aggregates atomically (e.g. activating one policy must mutate another
  policy/entity in the same instant), re-evaluate. First check whether it is really
  a business-process concern that belongs in workflow orchestration (Flowable)
  above the PDP, or an aggregate-boundary modeling error — resolve there before
  reintroducing multi-document transactions + replica set.
- **Conflict resolution between policies.** If ever needed, model it at **read
  time** (priority/weights/exclusion attributes evaluated by the PolicyEngine),
  never by coordinated writes across documents.

## Better option (impact if taken)

None preferred over head-pointer for this domain. The strongest *alternative* is
Model T, retained only if the internal deployment's already-provisioned replica
set is treated as the baseline and cross-document coordination is expected soon —
in which case T's transactional infrastructure is reusable. Given the PDP's
independent-aggregate nature and the YAGNI/asymmetric-regret argument (choosing P
and later migrating to T is cheap and driven by a real requirement; choosing T and
never using it is a permanent tax), head-pointer is the recommended path and T is
documented here only as the escape hatch it would be.
