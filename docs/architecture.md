# Architecture

This document describes the design of Service Policy, its current state, and the decisions that
shaped it. It is updated with each phase of development.

**Current state: Phase 1.5.** The PEP-facing REST contract is live and backed by a deterministic
stub evaluator. There is no persistence layer yet — MongoDB integration is Phase 2.

---

## Overview

Modern distributed systems routinely duplicate authorization logic across services: `if
(user.hasRole(...))` checks scattered across Java, Python, PHP, and Node.js codebases. The result
is inconsistency, audit gaps, and high maintenance cost when policies need to change.

Service Policy replaces scattered checks with a single HTTP service: **send it a question, get back
a decision**. "Can this subject perform this action on this resource, under these conditions?" The
caller (PEP) enforces the decision; Service Policy only evaluates.

---

## The XACML vocabulary

Service Policy uses the standard XACML terms:

|          Component          | Abbreviation |                                       Role                                        |
|-----------------------------|--------------|-----------------------------------------------------------------------------------|
| Policy Decision Point       | **PDP**      | Evaluates the authorization query and returns a decision. This is Service Policy. |
| Policy Enforcement Point    | **PEP**      | Sends the query and enforces the result. Your API gateway, service, or frontend.  |
| Policy Information Point    | **PIP**      | Supplies additional attributes not present in the request. Optional; Phase 2+.    |
| Policy Administration Point | **PAP**      | Manages policies — CRUD, versioning, audit. Phase 4.                              |

The PEP never knows the policy details. The PAP never enforces decisions. The PDP never contains
business logic. Each component has exactly one job.

---

## Three-plane architecture

Service Policy is designed for a three-plane security model common in enterprise and multi-tenant
government platforms:

```
┌─────────────────────────────────────────────────────────────┐
│  Configuration plane                                        │
│  Authz Admin (PAP) · DTI Core · other microservices         │
│  (manage policies via PAP API — Phase 4)                    │
└──────────────────────────┬──────────────────────────────────┘
                           │ policy CRUD (Phase 4)
┌──────────────────────────▼──────────────────────────────────┐
│  Security plane                                             │
│  Keycloak (identity/authn)                                  │
│  Service Policy (authorization / PDP)                       │
│  ← stub evaluator now; MongoDB-backed evaluator in Phase 2  │
└──────────────────────────┬──────────────────────────────────┘
                           │ /v1/permissions · /v1/evaluate
┌──────────────────────────▼──────────────────────────────────┐
│  Data plane                                                 │
│  Frontends → Fortinet → API Gateway → Backend services      │
│  (PEPs call Service Policy before serving requests)         │
└─────────────────────────────────────────────────────────────┘
```

The security plane is isolated. PEPs in the data plane call Service Policy over an internal
network. Frontends never call Service Policy directly.

---

## Endpoint design

### `GET /v1/permissions?app=<name>` — cacheable permission list

Returns all actions the authenticated subject is permitted to perform within a given application
context, as a flat list of `resource:verb` strings.

This response is **cacheable** by PEPs per `subject + app + policyVersion`. A TTL of 30–60 seconds
is typical. The `policyVersion` field changes whenever the active policy set changes; PEPs use it
as a cache-invalidation signal.

Typical use: populate frontend menus, show/hide navigation items, enable/disable buttons. Not
suitable for data-level decisions where resource attributes matter.

### `POST /v1/evaluate` — per-request authorization decision

Evaluates a single authorization query with full attribute context:

- Subject attributes from the JWT (Phase 3: validated JWKS; Phase 1.5: stub decode).
- Resource attributes supplied inline by the PEP in `ResourceRef.attributes` (ADR-005).
- Action string in `resource:verb` format.
- Optional runtime context (e.g. `emergency: true`).

Returns a `Decision`. Not suitable for bulk caching because it depends on specific resource state.

### `POST /v1/evaluate/batch` — batch decisions

Same as `/v1/evaluate` but accepts a list of requests and returns a list of decisions in the same
order. Designed for API gateways and BFFs that need multi-resource authorization in a single
round-trip without requiring N separate calls.

---

## Decision shape

`Decision` is **boolean-first**:

```json
{
  "allowed": true,
  "reason": "permitted by default stub policy",
  "decisionId": "a3f2c1d0-...",
  "policyVersion": "stub-0",
  "obligations": []
}
```

- `allowed` — the enforcement signal. The PEP acts on this field.
- `reason` — human-readable; for logs and debugging. Never to be parsed by PEPs.
- `decisionId` — UUID; correlates this decision with audit log entries.
- `policyVersion` — the policy set version that produced this decision.
- `obligations` — actions the PEP must carry out when enforcing (e.g. write audit entry at a
  specific level). Empty if none.

The XACML 4-valued result (Permit / Deny / Indeterminate / NotApplicable) may be added as an
optional field in a future phase without breaking existing PEPs.

---

## Subject and tenant

The **subject** is always resolved from the `Authorization: Bearer <jwt>` header — never from the
request body or a query parameter. This follows standard OAuth 2.0 / OIDC conventions and ensures
the PDP's authorization boundary matches the authentication boundary.

**Phase 1.5 (stub):** The JWT payload is base64url-decoded without signature verification. The
`sub` claim is used (fallback: `preferred_username`, else `"unknown"`). This is intentional and
documented; the stub is not safe for networked deployment.

**Phase 3:** Signature verification via Keycloak JWKS. The `iss` claim identifies the realm, which
equals the tenant (ADR-006).

**Tenancy model (ADR-006):** A tenant is one Keycloak realm. Service Policy runs as separate
deployments per tenant (silo model). Rules are versioned configuration in a baseline + overlay
model. CURP is stored as a standard Keycloak user attribute from day one as the canonical person
identifier.

---

## Resource attributes

The PEP supplies resource attributes **inline** in `ResourceRef.attributes` as the primary model.
Attributes are keyed by stable `id` or immutable `code` from the source catalog — never by display
name (ADR-005). Example:

```json
{
  "type": "documento",
  "id": "doc-42",
  "attributes": {
    "status_code": "draft",
    "owner_curp": "QURD870315HDFNRN06",
    "confidencial": false
  }
}
```

The PEP resolves stable codes before sending the request. The PDP never calls the source catalog to
translate display names.

PDP-side attribute resolution from its own store or an external PIP is a Phase 2/3 option for
attributes the PEP cannot reasonably know (e.g. dynamically computed quota). It is not the default
model.

---

## Policy model (Phase 2, planned)

Policies are stored as JSON documents in MongoDB. Each policy has:

- `effect` — `PERMIT` or `DENY`.
- `subject` — constraints on who (roles, realm, specific sub).
- `action` — list of action strings or patterns.
- `resource` — type and optional attribute constraints.
- `condition` — a JSON AST evaluated against the request (Phase 2, future ADR).

The AST supports two node shapes: comparison leaves (`EQ`, `NEQ`, `IN`, `NOT_IN`, `GT`, `GTE`,
`LT`, `LTE`) and combinators (`AND`, `OR`, `NOT`). Attribute paths address `subject.*`,
`resource.*`, `resource.attributes.*`, `context.*`, and `action`.

The default combining algorithm is **deny-overrides**: a single applicable DENY wins regardless of
any PERMIT. This is the safest default for an access-control system.

The real `PolicyEvaluator` implementation (Phase 2) will replace `StubPolicyEvaluator` without
changing the REST layer or the port interface.

---

## Control-plane authorization (ADR-033)

The control plane — authoring, activating, deactivating and reading policies, simulating them,
and administering an application's action catalogue and configuration — is authorized **per
application, by this engine, with its own policy model**. There is no administrative role.

```
request ── /v1/apps/{app}/… ──► ControlPlaneGate.authorize(app, action)
                                   │  app: the route's segment, as matched
                                   │  caller: sub + validated token (nothing from the body)
                                   ▼
             installation marker? ─ no ─► permitted only for the bootstrap subject
                                   │ yes
                                   ▼
   subject.attr.* ◄── token, through the claim mapping stored for the reserved application
   policies       ◄── active policies of the reserved application, resourceType "policy"
   resource.attr.app = app            action = policy:read|write|activate|deactivate
                                   ▼
                         deny-overrides decision ─► 403 FORBIDDEN (one fixed body) | proceed
```

**Provenance.** The subject of a control-plane decision is the caller itself, and its attributes
come from its validated token. The entry points take the route's application, the action and the
validated identity, and have no parameter that could carry a subject attribute; the
caller-asserted channel of ADR-010 stays on `/evaluate`, where the caller asserts facts about a
third party.

**Two roles of the reserved application.** Its configuration holds the claim mapping every
control-plane decision applies, and its policies are the control-plane policy set. It is never
the application being decided about unless the route names it. Installation seeds it with the
mapping from `service-policy.control-plane.subject-attributes.apps`, the catalogue of resource
type `policy`, and the baseline `permit when resource.attr.app IN subject.attr.apps`. Its
configuration cannot be created or deleted through the API, and a `PUT` that would leave its own
caller without the reserved application is refused.

**Finer grants.** They are policies in the reserved application, authored through the ordinary API.
Under deny-overrides, every policy selected for a verb must permit it — a policy that matches no
rule contributes its `defaultEffect` — so a grant that widens the baseline for one verb is written
into the policy that governs that verb, for example by giving `read` its own policy.

**Installation.** While no installation marker exists, only the bootstrap subject (a configured
claim value) is accepted. Its first successful control-plane write records the marker, which
nothing in the service deletes; installation mode is decided by the marker alone, never by the
absence of policies. The marker also records **which application is reserved**, and that is what
makes the identifier part of the installation rather than of the running configuration: a
deployment configured with a different one does not start, and one already running when another
instance closed installation denies every control-plane call from the marker read each decision
already makes — so no configuration change can move the control plane onto another application's
policies. Installation seeds only into an empty reserved application: a document there that
installation did not write — recognised by the audit it records on its own documents, never by
content — stops startup, and nothing is adopted. Seeding then converges on its end state, as every
multi-document write here does (ADR-019): what an interrupted start left half-written is completed
— a missing version 1 written, an inactive baseline activated — and the end state is read back; a
start that does not reach it refuses. Every instance of the previous version must be stopped before
this one starts: it honours the global marker and ignores the gate, so one left running could
replace the policy set seeded here, and nothing in this service can stop it. Once installed, the
stored claim mapping is the only source and its deployment property is ignored, with one warning if
it differs from a usable stored claim path.

**Startup refuses rather than warns** wherever the alternative is an installation nobody can
administer, or one administered by something nobody chose: a blank reserved identifier; not
installed and no claim path for `apps`; not installed and no bootstrap value; not installed and a
reserved application that already holds foreign documents, which the refusal names; not installed
and a seeding that did not reach its end state; a marker whose
recorded reserved application differs from the configured one; a marker from which this build
reads no identifier — the earlier shape, which predates the build, or one missing, malformed or of
an unknown shape, each said as what it is; and an installed store whose reserved application holds
no usable `apps` mapping. The one warning is the divergence above, where the service still works.

**Denials and the merged view.** The gate's denial is one fixed `403` body, decided before anything
about the route's application is read, so it cannot reveal whether the application exists. Two
refusals with their own bodies come *after* the gate has permitted the call — creating or deleting
the reserved application's configuration, and a simulated `request` for another subject without the
delegation marker — and neither is a function of whether the route's application exists: the first
depends only on the identifier the caller already holds, the second only on the body. The merged
catalogue `GET /v1/policies` is scoped to the applications the caller may read before the query
runs: totals and pages count only what is visible, and an empty scope is an empty `200`.

**Audit.** A write may declare on whose behalf it is made with the body field `subject`. It
authorizes nothing. Every control-plane write that **stores a document** records `createdBy` (the
calling credential), `subject`, and `subjectProvenance` — `VERIFIED` when the identity is the
token's own, `DECLARED` when the caller asserted it. A `DELETE` records nothing, because the
document it would have been recorded on is gone. The documents installation seeds carry all three,
with the installation identity and `VERIFIED`. That identity is installation's alone: a caller whose
subject resolves to it is denied every control-plane call.

The migration from the removed global marker is described in the README, under *Upgrading to
0.6.0*.

---

## Why this design

Several alternatives were evaluated before settling on this architecture.

### Keycloak Authorization Services (rejected)

Keycloak includes a built-in authorization server (UMA 2.0). Rejected because:

- **UMA 2.0 is heavy for SPA use cases.** The token exchange flow adds round-trips and complexity
  for simple permission checks.
- **Admin UI is developer-oriented.** Non-technical compliance teams cannot manage policies without
  training.
- **Audit log is limited.** Keycloak does not provide a queryable log of every authorization
  decision, only authentication events.
- **Vendor lock-in.** Coupling authorization logic to Keycloak makes it harder to migrate identity
  providers later.

### OPA + Rego (rejected for MVP)

Open Policy Agent is powerful but:

- **Rego has a steep learning curve.** Most teams need dedicated training before they can write
  non-trivial policies.
- **Requires operating a separate service.** OPA is general-purpose; Service Policy is opinionated
  and optimized for the ABAC use case.

Remains a viable option for teams already running OPA.

### Cedar (rejected for MVP)

Amazon's Cedar policy language is well-designed, but:

- **DSL learning curve.** Custom syntax requires onboarding for every team that writes policies.
- Java SDK maturity was limited at project start.

A migration path to Cedar is open if the JSON AST proves insufficient. The `PolicyEvaluator` port
isolates the REST layer from this potential change.

### XACML (rejected)

XACML 3.0 is the formal standard that inspired this design, but:

- **XML-verbose.** Policy files are difficult to read, write, and diff.
- **Effectively abandoned.** No major new implementations since 2013.

---

## Kubernetes-ready since day 1

Service Policy is built as a [12-factor app](https://12factor.net/) from day one:

- **Stateless.** No in-process state. Horizontal scaling is `kubectl scale`.
- **Health checks.** `/q/health/live` and `/q/health/ready` map directly to Kubernetes
  `livenessProbe` and `readinessProbe`.
- **Metrics.** Prometheus metrics at `/q/metrics` are compatible with Prometheus Operator scraping.
- **Structured logs.** JSON to stdout — compatible with Fluentd, Loki, and any log aggregation
  pipeline.
- **Config via env vars.** All configuration can be injected via `ConfigMap` / `Secret` without
  rebuilding the image.

Helm charts and Kubernetes manifests are planned for Phase 6.

---

## Roadmap reference

For the current status of each phase, see the
[Roadmap section in README.md](../README.md#roadmap).
