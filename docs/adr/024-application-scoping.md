# 24. Application scoping — `app` as a first-class policy dimension

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** Ricardo
- **Refines:** ADR-006 (tenancy: tenant = institution), ADR-008 (policy domain), ADR-010 (subject attribute provenance)

## Context

ADR-006 fixes tenancy at the **institution** level: one tenant = one Keycloak realm =
one silo deployment. Within a single silo, however, one PDP instance serves the
policies of **several applications** of the same institution. Three consumer
applications are now waiting on this authorization layer to begin configuring their
policies — the second-consumer threshold is not approaching, it has been passed.

The engine has no application dimension. `resourceType` is a **single global
namespace per instance**: if two applications each define a `document` type, their
policies are mixed at evaluation time and one application's policy can decide access
to another application's resources. The interim mitigation was an informal naming
convention (`resourceType = "<app>:<type>"`), enforced by nothing.

With one consumer that convention was survivable. With three it is not, for two
reasons:

- **It is a correctness risk, not an ergonomics one.** Nothing validates the prefix.
  A policy created as `document` (no prefix) applies to *every* application's
  documents. In an authorization engine that is a silent cross-application isolation
  failure — the worst class of bug this component can have.
- **It leaks into every PEP contract.** Each consumer must know and reproduce the
  prefix convention to build its requests, coupling three independent teams to an
  informal string format. That is the opposite of a neutral engine (ADR-001).

The isolation between applications must therefore be a property the **engine
guarantees**, not a convention the consumers remember.

## Decision

**`app` becomes a first-class scoping dimension, present in the policy, in the
evaluation request, and in policy selection.**

1. **Policy document:** `app` is a **required** field (string), set at create and
   immutable across the versions of a policy. It is projected on the read views
   (`PolicyHeadSummary`, `PolicyHeadView`, `PolicyVersionSummary`).
2. **Evaluation request:** `EvaluationRequest` carries `app` — the PEP declares which
   application it is acting for.
3. **Selection:** the evaluator selects policies by **`(app, resourceType, verb)`**.
   A policy is applicable only if its `app` equals the request's `app`.
   `PolicySelector` (domain) gains the `app` match alongside the existing
   resourceType/verb match; the active-head query narrows by `app` as well.
4. **`resourceType` is a per-app namespace.** Two applications may both define
   `document`; they never collide, because selection is scoped by `app`. The
   `"<app>:<type>"` prefix convention is **abandoned** — `resourceType` goes back to
   being a clean domain term.
5. **`GET /v1/policies` gains an optional `?app=` filter** (server-side), so the PAP
   can list and group policies per application without parsing names.
6. **The evaluator's query is indexed.** Selection narrows the active-head lookup to
   `{activeVersion: {$ne: null}, app, resourceType}`, backed by a compound index on
   `(app, resourceType, activeVersion)` created at startup alongside the existing
   unique indexes (`PolicyLifecycleIndexes`). With three consumers evaluating on the
   hot path, the index lands with the change rather than being deferred for evidence:
   the query is the data plane's critical path and its shape is now known.

### No `global` / wildcard scope

There is **no cross-application policy** in this decision. A policy belongs to exactly
one `app` and is evaluated only for that `app`. Since `app` is required, there is no
"absent app" semantics to define and no wildcard to accidentally acquire: the
isolation is total and structurally unrepresentable to violate — the same property
ADR-016 achieved for "at most one active version."

The use case for transversal rules (e.g. "sealed resources are never readable, in any
application") is real but not yet evidenced. It is deferred deliberately: see
"better option not omitted."

### Administrative scoping lives outside the engine

"Which applications may this administrator manage?" is **not** stored in the PDP. It
is a **subject attribute**, and ADR-010 already fixes the provenance of subject
attributes: the caller asserts them; the engine never persists them. The intended
composition, requiring no new engine capability:

- Keycloak holds the administrator's `apps` attribute → it travels in the JWT.
- The PAP, before writing a policy, asks the PDP to evaluate a **meta-policy**:
  `PERMIT if resource.attr.app IN subject.attr.apps`.
- The PDP evaluates it like any other policy, using the attributes supplied in the
  request. Nothing new is stored, nothing organizational enters the engine (ADR-001).

This is enabled by `app` being an attribute of the policy resource, which this ADR
provides. The write gate of the PDP itself stays **admin-binary** (ADR-013): the PAP
is the guardian of per-app administrative scope. See "consequences" for the residual
risk and the open question this leaves.

## Reasons

- **Isolation becomes structural, not disciplinary.** The engine cannot evaluate one
  application's policy against another's resource, regardless of what any consumer
  names its types. This is the same principle that makes ≤1-active irrepresentable.
- **Three consumers are waiting, none has shipped.** The contract change to
  `EvaluationRequest` is free right now and coordinated once. After three teams are in
  production it would require a coordinated rollout across all of them — the
  most expensive moment instead of the cheapest.
- **It removes a convention from every PEP contract.** Consumers stop encoding
  `"<app>:<type>"` and simply state their `app` and their own domain types.
- **Required, not optional.** There are no existing policies to preserve (development
  stage, all data recreatable), so making `app` required avoids an "absent app"
  semantics that would have to be fail-safe-defined and would remain a permanent trap.

## Alternatives considered

- **Keep the `"<app>:<type>"` prefix convention (status quo).** Zero engine change.
  Rejected: the convention is unvalidated, so a missing prefix silently produces a
  cross-application authorization decision; and it leaks into three PEP contracts.
- **Add `app` for listing/grouping only, defer evaluation scoping** (the earlier
  proposal). Rejected: it creates the field but not the guarantee — the data-plane
  collision risk persists, protected only by the same informal convention, while
  giving the impression the problem is addressed. For an authorization engine this is
  the worst of both.
- **`app`-level silo deployment (one PDP per application).** Rejected: ADR-006
  reserves silos for institutions; one deployment per application multiplies operations
  with no isolation requirement that scoping does not already satisfy.
- **Optional `app` with fail-safe absence semantics** (absent app = its own namespace,
  never a wildcard). Rejected in favour of required: with no data to preserve, a
  required field removes an entire class of edge cases and a permanent explanation.

### Better option not omitted (and its impact)

- **Explicit cross-application (`global`) policies for transversal rules.** A policy
  marked explicitly global (never by absence of a field) would apply to every
  application — useful for genuinely universal rules ("sealed resources are never
  readable"). Impact: it reintroduces a scope whose blast radius is every application,
  so it needs its own authorization treatment (creating a global policy should not be
  something an app administrator can do), and a mis-created permissive global rule
  grants access across all consumers at once. Deferred, not omitted: adopt it when a
  concrete transversal rule is actually shared by consumers — at that point the rule
  itself, the number of consumers sharing it, and whether duplicating it per app is in
  fact more auditable, will all be known. The trigger is a real shared rule, not a
  hypothetical one.

## Consequences

- **Contract change (data plane):** `EvaluationRequest` gains `app`. Every PEP must
  send it. Coordinated once, before any consumer ships.
- **Contract change (authoring):** `POST /v1/policies` requires `app`; it is immutable
  across versions of the policy.
- `resourceType` is now a per-app namespace; the prefix convention is dropped.
- Policy selection is `(app, resourceType, verb)`; the active-head query narrows by
  `app`, which also reduces the candidate set per evaluation. A compound index on
  `(app, resourceType, activeVersion)` is created at startup to serve it.
- The PAP gains `?app=` filtering and a stable grouping key.
- **Residual risk (open):** the PDP's write gate remains admin-binary — any holder of
  the admin marker can author a policy for any `app`. Per-app administrative scope is
  enforced by the PAP (via the meta-policy above), not by the engine. This is
  acceptable while the PAP's service account is the only holder of the marker. If
  another system ever obtains it, the engine needs defense in depth (validate that the
  policy's `app` is within the caller's `apps` claim) — a change to ADR-013. This is
  the same trust-the-gateway vs. defense-in-depth question already open for PEP
  revalidation.

## Criteria to revisit

- A transversal rule is genuinely shared by multiple applications → introduce explicit
  `global` scope (with its own authorization treatment), per the deferred option.
- Another system obtains the admin marker, or per-app authoring must be enforced by the
  engine rather than trusted to the PAP → extend ADR-013 with per-app write gating.
- An application needs to read another application's policies (cross-app
  administration/audit) → revisit whether `?app=` filtering suffices or a broader
  administrative view is required.

