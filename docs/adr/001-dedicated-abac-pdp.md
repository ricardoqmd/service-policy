# ADR-001: Dedicated ABAC Policy Decision Point

| Field  |             Value             |
|--------|-------------------------------|
| Status | Accepted                      |
| Date   | 2026-05-06                    |
| Author | Ricardo Quintero Mármol Durán |

---

## Context

Authorization logic is the single most commonly duplicated cross-cutting concern in distributed
systems. In a typical multi-service deployment, the question "can this subject do this?" is answered
independently by each service — `if (user.hasRole(...))` guards scattered across Java, Python, PHP,
and Node.js codebases with no single source of truth.

This creates concrete problems:

- **Inconsistency.** Two services may answer the same question differently because their logic
  diverged.
- **Audit gaps.** No single queryable log of every authorization decision exists; compliance
  evidence must be assembled from N service logs.
- **High change cost.** A policy change (e.g. a new role) requires coordinated deploys across all
  affected services.
- **Language heterogeneity.** Authorization logic must be reimplemented in every programming
  language used in the platform.

The platform also requires **Attribute-Based Access Control** (ABAC) — decisions that depend on
the attributes of the subject, the resource, and the context — not just role membership. Role-based
guards become unwieldy when fine-grained conditions are involved (e.g. "the requester is the owner
AND the record is in draft status AND the request originates from within the office network").

---

## Decision

Build a **dedicated HTTP Policy Decision Point** service that:

- Centralizes all authorization decisions for the platform.
- Implements ABAC — decisions based on subject attributes (JWT claims), resource attributes
  (supplied inline or fetched from the PDP store), and request context.
- Exposes a simple REST interface (`/v1/evaluate`, `/v1/evaluate/batch`, `/v1/permissions`) callable
  by any service in any language.
- Is **stateless** at the HTTP layer — it holds policies, not sessions.
- Follows the XACML conceptual model: PEP (enforces) / PDP (decides) / PAP (administers) as
  separate concerns, even if PAP is a later phase.

---

## Alternatives considered

### Embed authorization in each service

**Rejected.** Does not solve inconsistency, audit gaps, or change cost. Each service still needs
to implement and maintain its own logic.

### Keycloak Authorization Services (UMA 2.0)

**Rejected.** Already operates Keycloak for identity; using its built-in authorization server was
evaluated. UMA 2.0 is heavyweight for simple PEP-to-PDP calls, the admin UI targets developers not
policy authors, and decision audit is not a first-class feature. Coupling authorization logic to a
specific IdP also creates lock-in if identity providers change.

### Open Policy Agent (OPA)

**Rejected for this project.** OPA is a capable general-purpose policy engine, but Rego has a
steep learning curve for teams without prior OPA experience, and running OPA as a sidecar or
central service adds operational complexity. The JSON AST policy format chosen here is simpler for
the team's context.

### Casbin

**Rejected.** Casbin is a library, not a service. It would require each service to embed it,
returning to the N-implementation problem. It also lacks a built-in audit log.

---

## Consequences

**Positive:**
- Single source of truth for all authorization decisions on the platform.
- ABAC conditions are evaluated consistently regardless of which service calls the PDP.
- Every decision can be logged in a structured, queryable format (Phase 4).
- Consumer services in any language make the same HTTP call — no per-language library.

**Negative:**
- Adds a network hop for every authorization check. Mitigated by the cacheable
`GET /v1/permissions` endpoint for bulk lookups.
- The PDP becomes a shared dependency: its availability affects all consumers. Mitigated by
Kubernetes horizontal scaling and the PDP's stateless design.
