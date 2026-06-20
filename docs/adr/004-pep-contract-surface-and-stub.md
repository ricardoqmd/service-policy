# ADR-004: PEP Contract Surface and Stub Evaluator (Phase 1.5)

| Field  |             Value             |
|--------|-------------------------------|
| Status | Accepted                      |
| Date   | 2026-06-16                    |
| Author | Ricardo Quintero Mármol Durán |

---

## Context

Phase 1 delivered the skeleton: Quarkus + health + metrics + CI. Before introducing persistence
(Phase 2) or real JWT validation (Phase 3), the PEP-facing REST contract must be frozen so that
consumer teams can integrate against a stable API surface.

Freezing the contract early also provides a concrete `docs/openapi.yaml` artifact that acts as a
machine-readable specification — useful for API gateways, client code generation, and contract
tests.

---

## Decision

Introduce the full PEP REST surface in Phase 1.5, backed by a deterministic in-memory stub
evaluator behind a port interface.

### REST endpoints

| Method |         Path         |                    Description                     |
|--------|----------------------|----------------------------------------------------|
| POST   | `/v1/evaluate`       | Single authorization request → `Decision`          |
| POST   | `/v1/evaluate/batch` | Batch of requests → list of `Decision`             |
| GET    | `/v1/permissions`    | Cacheable flat permission list for a subject + app |

### Subject resolution

The subject is always read from the `Authorization: Bearer <jwt>` header, never from the request
body. The stub decodes the JWT payload without signature verification (`sub` claim, fallback
`preferred_username`, else `"unknown"`). Real OIDC validation via `quarkus-oidc` + JWKS is deferred
to Phase 3 (ADR-003).

### Decision shape

`Decision` is boolean-first (`allowed: true/false`) with a human-readable `reason`, a `decisionId`
(UUID) for audit correlation, a `policyVersion`, and an `obligations` list. This is intentionally
simpler than XACML's indeterminate/not-applicable states — those can be added in a later phase if
needed.

### `GET /v1/permissions` cacheability

`/v1/permissions` returns a flat list of permitted action strings for a subject within an
application context. The response is cacheable by PEPs (e.g. 30-second TTL) using
`subject + app + policyVersion` as the cache key. The `policyVersion` field changes whenever the
active policy set changes, acting as a cache-invalidation signal.

### Inline resource attributes

`ResourceRef` accepts an optional `attributes` map so PEPs can pass resource attributes inline
without a separate lookup. Keys follow the attribute id/code convention defined in ADR-005. This
covers Phase 1.5; Mongo-backed attribute resolution is Phase 2.

### Port + stub pattern

`PolicyEvaluator` is a Java interface (the port). `StubPolicyEvaluator` is the `@ApplicationScoped`
implementation active in Phase 1.5. Its rules are deterministic:

1. `context.emergency == true` → allowed=true + `audit` obligation (`level: high`).
2. `resource.attributes.confidencial == true` → allowed=false.
3. Verb (substring after `:` in action) is in `denied-verbs` config → allowed=false.
4. Otherwise → allowed=true.

A persistence-backed implementation will replace the stub in Phase 2 without changing the REST layer
or the `PolicyEvaluator` interface. The combining algorithm for multiple applicable policies is a
Phase 2 concern (future ADR).

### Batch endpoint

`POST /v1/evaluate/batch` is included from Phase 1.5 to avoid a future breaking change. API
gateways and frontend BFFs regularly need multi-resource authorization in a single round-trip.

### OpenAPI schema as frozen contract

`quarkus.smallrye-openapi.store-schema-directory: docs` emits `docs/openapi.yaml` at build time.
This file is committed to the repository and serves as the frozen contract artifact for consumer
teams and API gateway configuration.

---

## Alternatives considered

### Defer the REST surface to Phase 2

Would keep the skeleton clean but forces consumer teams to wait for MongoDB integration before they
can integrate. The stub gives them a working target immediately.

### Accept subject from the request body

Simpler for PEPs that don't forward JWTs. Rejected: it would make the API inconsistent with
standard OAuth 2.0 / OIDC patterns and would require a breaking change when real auth is introduced.

### XACML-style indeterminate/not-applicable

Adds precision but significantly increases the PEP integration burden. The boolean `allowed` field
covers all Phase 1–5 use cases. Indeterminate states can be added as an optional field later without
a breaking change.

---

## Consequences

**Positive:**
- Consumer teams have a stable, documented API surface before any persistence is ready.
- `docs/openapi.yaml` gives API gateways and code generators a machine-readable contract.
- The port interface (`PolicyEvaluator`) isolates the REST layer from persistence changes in Phase 2.
- Tests cover all deterministic stub rules; adding tests for the real evaluator in Phase 2 requires
no REST test changes.

**Negative:**
- The stub silently accepts any JWT without signature verification. This is safe only in local dev
and CI; the service MUST NOT be deployed to any networked environment before Phase 3.
- `StubPolicyEvaluator` must be removed (not just disabled) in Phase 3 to avoid it accidentally
becoming the active bean after the OIDC evaluator is wired in.
