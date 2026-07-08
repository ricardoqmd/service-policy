# 20. Activation write-path — explicit-version activate and deactivate

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** Ricardo
- **Refines:** ADR-014 (explicit activation, ≤1-active invariant), ADR-016 (head-pointer single-doc activation), ADR-018 (conditional writes), ADR-019 (transaction-free atomicity)

## Context

ADR-014 established that a policy reaches production only through an explicit
activation step, with the invariant that at most one version is active per policy.
ADR-016 chose the head-pointer model, in which activation is a single-document
update on the head (`activeVersion` + `activeContent`), removing the replica-set
requirement. Neither the store method nor the HTTP endpoint for activation exists
yet: after ADR-019 (S2 writes), `PolicyLifecycleStore` has `create` and `append`
but no `activate`/`deactivate`, so every policy created so far is inactive and
unreachable by production.

This ADR defines the activation write-path. It is the prerequisite for the
evaluator cutover (a separate ADR): the evaluator cannot usefully read
`activeContent` until a path exists that writes it. Two questions must be settled:
which version activation targets, and how the "`activeContent` is written only by
activation" integrity property is enforced now that `activeContent` is about to
become the sole input to `/evaluate`.

## Decision

### 1. `activate` targets an explicit version

Activation is `POST /v1/policies/{id}/activate` with body `{"version": N}`,
admin-gated (ADR-013), and a conditional write (ADR-018): it requires `If-Match`
with the head's current ETag (= `revision`).

Activation is a single-document update on the head, mirroring the ADR-019 CAS
pattern used by `append`:

1. **Read version `N`** (`policy_versions` by `(policyId, N)`) to obtain its
   immutable `content`. Versions are append-only and never deleted, so a version
   that exists cannot vanish before the write.
2. **Compare-and-set on the head** — the commit point and serialization point in
   one write:
   `updateOne({policyId, revision: ifMatch}, {$set: {activeVersion: N, activeContent: <version N content>}, $inc: {revision: 1}})`.
   - `matchedCount == 1` → **200**, head now active on version `N`, new ETag returned.
   - `matchedCount == 0` → precondition failed; disambiguate on the failure path: a
     head exists → **412 `PRECONDITION_FAILED`** (carrying `currentRevision`); no
     head → **404 `POLICY_NOT_FOUND`**.

If step 1 finds no version: a head exists → **404 `VERSION_NOT_FOUND`**; no head →
**404 `POLICY_NOT_FOUND`**.

Because `activeVersion` is a single field on a single head document, the
**≤1-active invariant holds for free** (ADR-014): activation replaces the pointer,
it cannot produce two active versions.

### 2. `deactivate` clears the active pointer

`POST /v1/policies/{id}/deactivate`, admin-gated, conditional (`If-Match`), no body.
It is a soft retire (ADR-014, no hard-delete): a single-document CAS update
`{$set: {activeVersion: null, activeContent: null}, $inc: {revision: 1}}` under the
same `{policyId, revision: ifMatch}` filter, with the same `matchedCount == 0`
disambiguation (412 vs 404). The version history is untouched; only the head's
active pointer is cleared.

### 3. Conditional-write and idempotency semantics

Missing `If-Match` on either endpoint → **428 `PRECONDITION_REQUIRED`** (ADR-018);
the CAS never runs unconditionally. Any head mutation — including activation and
deactivation — bumps `revision`, so the ETag changes and the uniform token covers
append, activate, and deactivate alike (ADR-018).

Re-activating the currently-active version, or deactivating an already-inactive
policy, is allowed and simply re-asserts the state; it still bumps `revision`
(any head write does). `revision` is opaque, so a no-op re-assert that advances it
is harmless. Guarding no-op re-asserts is deferred (see "better option not omitted").

**No new error codes.** activate/deactivate reuse the ADR-018 catalog
(`VERSION_NOT_FOUND`, `PRECONDITION_FAILED`, `PRECONDITION_REQUIRED`,
`POLICY_NOT_FOUND`, `FORBIDDEN`); `docs/ERRORS.md` is unchanged.

### 4. `activeContent` integrity: activation is the sole writer

Now that `activeContent` is about to become the sole input to evaluation, the
property "only activation writes `activeContent`" is a correctness/security
invariant, not cosmetics: any other path that set `activeContent` would make a
policy silently evaluable without an explicit, admin-gated activation.

This invariant is enforced **behaviorally**: a persistence test asserts that after
`create` and after `append`, both `activeVersion` and `activeContent` are `null`,
and that only `activate`/`deactivate` change them. This directly tests the
observable effect. It is not enforced structurally today (see below).

## Reasons

- **Explicit version is the safe default.** Activation names exactly what goes to
  production; an author must sign off on the specific version. The alternative
  (activate-latest) would let an unreviewed `append` reach production implicitly.
- **Reuses the ADR-019 single-doc CAS.** activate/deactivate are one-document head
  writes, so the existing transaction-free atomicity and the append disambiguation
  pattern apply unchanged; the version read is a precondition lookup, not a second
  write, so single-doc atomicity holds.
- **≤1-active is structural, not enforced by code.** The single `activeVersion`
  field makes multiple active versions unrepresentable.
- **No new error surface.** The R018 catalog already covers every failure mode.

## Alternatives considered

- **Activate the latest version implicitly** (no `version` in the body). Rejected:
  couples activation to whatever was last appended, so an unreviewed or accidental
  `append` auto-promotes to production. Explicit version keeps activation a
  deliberate, auditable act.
- **`DELETE /v1/policies/{id}` for deactivation.** Rejected: `DELETE` implies
  removal; deactivation is a soft retire that preserves version history (ADR-014).
  A `deactivate` action sub-resource states the intent without implying deletion.
- **Encode active state as a `PUT` on a head state field.** Rejected: activation is
  an action with side conditions (version must exist, admin-gated), not a plain
  field replacement; action sub-resources read more clearly and keep the
  conditional/authorization semantics explicit.

### Better option not omitted (and its impact)

- **Structural enforcement of the `activeContent`-write invariant via a single
  choke point + ArchUnit.** The strongest guarantee: route every `activeContent`
  mutation through one typed method and have ArchUnit assert only `activate`/
  `deactivate` call it, making the invariant exhaustive and machine-checked rather
  than example-based. Impact: `activeContent` is written through a Mongo
  string-keyed update (`$set "activeContent"`), which ArchUnit cannot distinguish
  from any other write, so enforcing it structurally requires introducing a
  choke-point abstraction over what are today exactly two call sites (activate,
  deactivate). That is a premature abstraction against "promote at the second
  consumer / boring over clever." Deferred, not omitted: if a third path ever needs
  to touch `activeContent`, promote to the choke point and add the structural
  ArchUnit rule then. Until then, the behavioral test is the enforcement.
- **Guard no-op re-asserts** (skip the `revision` bump when activating the already-
  active version or deactivating an already-inactive policy). Impact: avoids a
  surprising ETag change on a state-preserving call, at the cost of a read-compare
  branch before the CAS and a second "did anything change" semantics. Deferred: the
  bump is harmless because `revision` is opaque, and the extra branch is not worth
  it for a rare call.

## Consequences

- `PolicyLifecycleStore` gains `activate(policyId, version, ifMatch, subject,
  reason)` and `deactivate(policyId, ifMatch, subject, reason)`; `PolicyResource`
  gains the two admin-gated, conditional action endpoints. Both write audit
  metadata to the head.
- Activation is transaction-free single-doc atomic (ADR-019), conditional (ADR-018),
  and preserves ≤1-active structurally (ADR-014/016).
- The `activeContent`-write invariant is enforced by a behavioral persistence test;
  its structural (ArchUnit) enforcement is deferred to a third consumer.
- The append-style benign race applies here too: when a CAS matches zero and the
  store re-reads the head to classify 412 vs 404, a concurrent create landing
  between the CAS and the re-read can yield 412 instead of 404. Accepted for the
  same reasons as ADR-019: no corruption, self-correcting on retry.
- This ADR is the prerequisite for the evaluator cutover (next ADR); it does not
  itself change what `/evaluate` reads.

## Criteria to revisit

- A third write-path needs to touch `activeContent` → introduce the choke-point
  abstraction and the structural ArchUnit rule (promote from behavioral to
  structural enforcement).
- Activation needs to target something other than a concrete version (e.g. a label
  or channel) → revisit the explicit-version contract.
- No-op re-asserts become operationally noisy (spurious ETag churn observed) →
  add the no-op guard deferred above.

