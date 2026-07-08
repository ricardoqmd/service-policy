# 21. Evaluator cutover to the head-pointer model

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** Ricardo
- **Refines:** ADR-016 (completes the head-pointer migration), ADR-008 / ADR-010 (persistent evaluator source), ADR-020 (depends on the activation write-path)

## Context

ADR-016 introduced the head-pointer model (`policy_heads` + `policy_versions`) and
moved activation onto it, but the evaluator was left reading the original
`policies` collection: `PersistentPolicyEvaluator` calls
`PolicyStore.activePoliciesFor(type)`, which queries
`{'active': true, 'content.resourceType': ?}` on `policies`. ADR-019 migrated the
write path to the head model (create/append) and ADR-020 added the activation
write-path, so `activeContent` on the head is now a real, explicitly-activated,
admin-gated artifact.

This leaves the system with two persistence models: writes and activation target
the head model, while evaluation still reads the legacy collection. That split was
the accepted transitional window of ADR-019's Option A. This ADR closes it: the
evaluator reads the head model, and the legacy single-collection path is removed.

The database holds no production data (this is a portfolio/reference service and
the collection was never populated with real policies), so there is no data to
migrate — the cutover is code-only, and the old model can be deleted rather than
deprecated.

## Decision

**The head-pointer model becomes the sole source of policy evaluation, in a single
hard switch, and the legacy single-collection path is deleted.** No dual-read, no
transitional coexistence, no deprecation period.

### 1. Evaluator reads active heads by resource type

`PersistentPolicyEvaluator` stops calling `PolicyStore` and instead loads active
heads for the requested resource type from the head model, mapping each head's
`activeContent` to a domain `Policy`:

- A new store method `PolicyLifecycleStore.activePoliciesFor(resourceType)` returns
  the domain policies, backed by a new repository query
  `PolicyHeadRepository.findActiveByResourceType(resourceType)` filtering
  `{'activeVersion': {$ne: null}, 'resourceType': ?1}`.
- Each head's `activeContent` is mapped with the **same** `PolicyDocumentMapper.
  fromDocument` the legacy path used. Evaluation logic is unchanged; only the source
  of the active content changes (`head.activeContent` instead of `policies.content`).
- The evaluator depends on the **store**, not the repository directly, preserving
  the `evaluation → persistence store → repository` layering.

**Correctness constraints:**

- The query must return the **complete** active set for the resource type, not a
  page. It is distinct from the paginated `findActiveHeads` used by the admin list
  endpoint; the evaluator needs every applicable candidate.
- One active head per `policyId` yields at most one active content per policy, so
  the candidate set needs no de-duplication. This is stronger than the legacy model,
  which could in principle hold multiple `active: true` documents for one `policyId`.

### 2. Delete the legacy path

Removed entirely: `PolicyStore`, `PolicyRepository`, `PolicyDocument`
(`@MongoEntity("policies")`), and `PolicyStoreTest`. The `{@link PolicyStore}`
reference in `PolicyLifecycleStore`'s Javadoc is rewritten. Tests that seeded
through the legacy model (`EvaluatePolicyScenariosTest`, `PolicyResourceTest`) are
rewritten to seed through the head model — `create` then `activate` (ADR-020) —
which also makes them exercise the real production path end to end.

Kept: `PolicyDocumentMapper` and `ConditionDocumentMapper`, which map policy content
and are used by the head model and the read surface; they are not legacy.

The `policies` collection is abandoned (no reads, no writes). No drop is scripted;
an empty, unreferenced collection is inert.

## Reasons

- **One source of truth.** After the cutover there is a single persistence model for
  reads, writes, and activation. The transitional double-truth of ADR-019 Option A
  was explicitly temporary; this removes it.
- **The cutover is low-risk because evaluation logic does not change.** The engine,
  selector, and content mapper are identical; only the query that supplies active
  content is replaced. The behavior is pinned by porting the existing evaluation
  scenario tests onto the head model.
- **Delete over deprecate, given no data and a final-version goal.** Leaving
  `PolicyStore` in place (even `@Deprecated`) keeps a live, callable path to the old
  collection and dead code SonarCloud would flag. With an empty database and no
  external consumers, deletion is the honest end state.
- **Tightens an invariant.** The head model structurally guarantees one active
  content per policy; the legacy model relied on discipline to keep a single
  `active: true` document.

## Alternatives considered

- **Dual-read during a transition** (evaluator reads both models, union or
  head-preferred). Rejected: reintroduces the temporary double-truth ADR-019 Option
  A already rejected, and there is no migration pressure (empty database) that would
  justify it.
- **Deprecate-then-delete** (keep `PolicyStore` `@Deprecated` for a release).
  Rejected: leaves a live path to the abandoned collection and dead code, against the
  final-version, no-legacy-maintenance goal; deletion is safe because nothing
  populates or reads the collection after the switch.
- **Materialized read projection for evaluation** (a separate optimized view fed
  from heads). Rejected: over-engineering for the current scale (small active sets,
  <50ms p95 target); the head query is a direct, indexed lookup.

### Better option not omitted (and its impact)

- **Add an index on `policy_heads.(resourceType, activeVersion)` for the evaluator
  query.** The strongest performance option: the evaluator's hot-path query filters
  on `resourceType` + non-null `activeVersion`, and a compound index would keep it
  sublinear as the head count grows. Impact: a new index to create and maintain in
  `PolicyLifecycleIndexes`, plus write-time cost on head upserts. Deferred, not
  omitted: at MVP scale a collection scan over a small head set is fine, and adding
  an index preventively contradicts "evidence-driven — promote at the second
  consumer." Revisit when active-head volume or evaluation latency makes it measurable
  (this is recorded as a revisit criterion below).

## Consequences

- Policies created (ADR-019) and activated (ADR-020) become visible to `/evaluate`;
  the ADR-019 Option A transitional window is closed.
- `PolicyStore`, `PolicyRepository`, `PolicyDocument`, and `PolicyStoreTest` no
  longer exist; the `policies` collection is unused. Any tooling or doc that
  referenced them must be updated (none known in-repo beyond the Javadoc link).
- The evaluator now depends on `PolicyLifecycleStore`; the layering
  `evaluation → persistence store → repository` is uniform across read, write, and
  evaluate, which the forthcoming ArchUnit layering harness can assert.
- Evaluation of an inactive or never-activated policy returns no candidate for it —
  a policy is evaluable only after explicit activation (ADR-020), which is the
  intended security posture.
- The evaluator query is a scan over active heads of a resource type; unindexed
  today (see better-option-not-omitted).

## Criteria to revisit

- Active-head volume or `/evaluate` latency becomes measurable → add the
  `(resourceType, activeVersion)` index (or a projection) backed by evidence.
- A second read model of active policies appears (e.g. bulk permissions with its own
  shape) → reconsider whether a shared active-policy read abstraction is warranted.
- Real data ever lands in a legacy collection in another deployment → a one-off
  migration, not a dual-read, would be the path.

