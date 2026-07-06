# 14. Policy lifecycle and CRUD contract (/v1/policies)

Date: 2026-07-05

## Status

Accepted

Refines ADR-012 (policy authoring): a created policy is now **inactive by default**;
activation becomes an explicit, gate-able act. Builds on ADR-013 (all `/v1/policies*`
require the admin marker) and ADR-008 (neutral MVP domain). Append-only: ADR-012 is
not renumbered.

## Context

ADR-012 shipped `POST /v1/policies` (create-only, immediately active). The PAP needs
the full lifecycle — list, read, new versions, rollback, retire. Three constraints shape it:

- **Immutable versioning** (a stored version is never overwritten; a change is a new
  version) makes a naive `PUT`-as-replace wrong: `PUT` must *append a version*, not
  mutate one.
- **Activation is where governance lives.** The department has no dedicated reviewer
  role today — the author, a project lead, or a lone developer approves — but the
  architecture must let "review before a policy goes live" be **made required** without
  a redesign. Activation must therefore be a distinct, interceptable step, not a side
  effect of writing.
- Activation touches two documents (deactivate the current version, activate the new
  one) and must be atomic. That needs a MongoDB **transaction**, which needs a
  **replica-set** deployment — made a PDP deployment requirement (single-node replica set
  minimum). A transaction-less (standalone) deployment falls back to a degraded
  ordered-writes mode (§7).

## Decision

**1. Addressing.** `{id}` in the path is the logical `policyId` (opaque, ADR-005),
not the Mongo `_id`. Versions live under it.

**2. Authorization.** Every `/v1/policies*` endpoint (read and write) requires the
**admin marker** per ADR-013 §4: `401` without a valid Bearer, `403` without the marker.
Reading policy definitions is a control-plane operation, not a public one.

**3. Read surface.**
- `GET /v1/policies` — list of **active heads**: one entry per policy (its active
  version, or none). Filter `?resourceType=`. Offset pagination `?page=&size=`.
- `GET /v1/policies/{id}` — the **active** version of the policy; `404` if the policy
  has no active version.
- `GET /v1/policies/{id}/versions` — version history, newest first.
- `GET /v1/policies/{id}/versions/{version}` — a specific immutable version.

**4. Write surface — versions are append-only, activation is explicit.**
- `POST /v1/policies` — create a **new** policy at `version 1`, **inactive** (refines
  ADR-012: no longer auto-active). `409` if the id already exists.
- `PUT /v1/policies/{id}` — append the next version (`N+1`) with new content,
  **inactive**. The body declares `baseVersion` = the version the author forked from;
  if the current highest version ≠ `baseVersion`, `409` (optimistic concurrency —
  prevents lost updates between two admins). Existing versions are never mutated.
- `POST /v1/policies/{id}/activate` — body `{ version }`. The **single** path by which
  any version becomes active; enforces the **≤1-active-per-policy** invariant.
- `POST /v1/policies/{id}/deactivate` — retire the active version: the policy stops
  governing, history is preserved. There is **no hard delete** (LFPDPPP / NOM-024:
  policy history is audit evidence).

**5. Optional review gate (the "required by architecture" seam).**
`service-policy.authz.review.required` (default `false`).
- `false` → an author may activate their own versions (frictionless; the lone-developer
  and self-approval cases).
- `true` → `activate` returns `403` when the caller equals the target version's
  `createdBy` (four-eyes). No dedicated reviewer role is introduced — the existing admin
  marker held by a *different* identity is the gate.

**6. Audit metadata (persistence side, not domain).** Each version stores `createdBy`
(the validated caller `sub`, ADR-013), `createdAt`, and an optional `changeReason` from
the body. Activation stores `activatedBy` / `activatedAt`. This feeds the four-eyes check
and the future audit log; the pure domain `Policy` record is unchanged (`version:int`,
no `status` / `previousVersion`).

**7. ≤1-active atomicity — multi-document transaction.** `activate` flips both documents
(deactivate the current active version, activate the target) inside a **single MongoDB
transaction**, so the invariant has no visible intermediate state. This requires the
service to run against a **replica set** (single-node minimum), stated as a
deployment/adoption requirement.
**Degraded fallback (transaction-less deployment):** ordered writes — deactivate first,
then activate. The transient window has *no* active version → `defaultEffect = deny`
(fail-safe over-deny, never an over-permit); a partial failure leaves the policy inactive
and is retried. Offered only for deployments that cannot run a replica set.

## Reasons

- **Inactive-on-write + explicit activate** makes activation a single interceptable
  choke point, so "review required" is a config flag (§5), not a redesign — honoring the
  stated architectural need while keeping the default frictionless. Create and update
  behave **identically** w.r.t. activation: uniform, boring, easy to reason about.
- **Append-only `PUT`** is the only correct reading of `PUT` under immutable versioning;
  `baseVersion` turns a rare concurrent edit into a clean `409` instead of a silent lost
  update, at the cost of one integer compare.
- **A transaction is simpler to reason about** than ordered writes plus a transient
  window plus a retry story — the boring choice once a replica set is available. The
  ordered-writes fallback is kept only for transaction-less deployments, and even there
  the window is fail-safe (a momentary default-deny), consistent with ADR-011 and
  deny-overrides.
- **Soft-retire only**, because policy history is compliance evidence; a `DELETE` verb
  that does not delete would be dishonest — hence an explicit `deactivate`.
- **Audit fields in persistence, not domain,** keep the pure evaluation core (`Policy`)
  free of control-plane concerns.

## Alternatives considered

**A) Activate-on-write (single call).** `PUT` creates version N+1 and activates it in one
step; `activate` exists only to roll back to an older version.

> **Best-option note (impact of choosing A instead).** A is the most boring, fewest-calls
> option and would leave ADR-012 unchanged. It is rejected only because it makes activation
> a *side effect* of writing, so "review before go-live" cannot be inserted later without
> changing `PUT`'s behavior (a breaking change). Since no consumer yet depends on
> auto-activation, paying the small uniform explicit-activate cost now buys the review seam
> for free. If the department commits that review will **never** be required, collapse to A.
> Named, not omitted.

**B) Full draft → submit → approve status lifecycle.** A `status` enum
(`draft / submitted / active / superseded`), a `submit` step, and an approval audit trail.

> **Best-option note (the richer target).** B is the complete governance model: staged
> review, multi-party approval, full lifecycle visibility in the PAP. It is **deferred**,
> not dismissed. R014's explicit-activate + optional four-eyes is a strict subset that
> already delivers "review can be required"; B adds *multi-stage* review (an approver
> distinct from both author and activator, submission queues, rejection with reasons).
> Migrating R014 → B is additive: `active:boolean` becomes one value of the status enum.
> **Reopen when** a real approval-workflow consumer appears, or a two-state model can no
> longer express the required review stages.

**T) Transactional zero-window activation via a replica set.** *Adopted (§7).* Running
against a replica set (single-node minimum) lets `activate` commit both documents in one
transaction, removing the intermediate window entirely and keeping the hot evaluation read
path as a single indexed query (unlike the pointer-document remodel, alternative P). The
replica-set deployment is a stated PDP requirement; the ordered-writes mode above is kept
only as the degraded fallback for transaction-less deployments.

**P) Active-version pointer document** (an append-only version store plus a single mutable
`{policyId → activeVersion}` document, flipped atomically). Rejected here: it moves cost
onto the **hot evaluation read path** (a second lookup, or `resourceType` denormalization)
to optimize a rare write — the wrong trade for a read-dominated PDP.

## Consequences

- The PAP gets a complete, uniform lifecycle — create, list, read, version, rollback,
  retire — all admin-gated (ADR-013).
- ADR-012's "create → active" becomes "create → inactive; activate to publish." No live
  consumer depends on the old behavior; the PAP UI can offer a combined "Save & publish"
  (two calls under the hood) to preserve one-click ergonomics.
- Activation is atomic (a transaction) on the required replica-set deployment; the
  ordered-writes fallback keeps a fail-safe sub-second window for transaction-less setups.
- The service now **requires a replica set** (single-node minimum) — recorded in its
  adoption criteria.
- `PolicyStore` grows real operations (`findActiveHead`, `listActiveHeads`, `versionsOf`,
  `getVersion`, `appendVersion`, `activate`, `deactivate`) plus audit metadata; the pure
  domain `Policy` is untouched.
- Four-eyes review is available behind one flag, off by default.

## Revisit if

- A dedicated multi-stage approval workflow is needed → promote alternative B.
- A consumer genuinely cannot run a replica set → they take the degraded ordered-writes
  fallback (§7), accepting the fail-safe window.
- Concurrent policy edits prove common enough that the `409` friction hurts → consider
  merge/rebase affordances (today `baseVersion` + re-fork is sufficient).
- Offset pagination strains at scale → move `GET /v1/policies` to cursor pagination.
- Reads must be visible to non-admin operators → split a read-only marker from the admin
  marker (today all `/v1/policies*` share the admin gate per ADR-013 §4).
