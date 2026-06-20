# ADR-006: Tenancy Model — Tenant = Institution = Realm; Silo Deployment; Rules as Config

| Field  |             Value             |
|--------|-------------------------------|
| Status | Accepted                      |
| Date   | 2026-06-19                    |
| Author | Ricardo Quintero Mármol Durán |

---

## Context

Service Policy is designed for a government / enterprise context where multiple institutions
(tenants) must be supported. The design must decide:

1. How a tenant is defined and identified.
2. Whether to run one shared instance with tenant isolation or separate deployments per tenant.
3. How authorization rules are delivered per tenant.
4. How person identity is handled across tenants without merging user stores.

---

## Decision

### 1. Tenant = Keycloak realm

A **tenant** is defined as one **Keycloak realm**. The realm is derived from the `iss` (issuer)
claim in the validated JWT. It is **never** accepted from the request body or query string.

This makes the tenant boundary identical to the authentication domain boundary: a JWT issued by
realm `A` cannot be used to make decisions against policies of realm `B`, because the signature
validation step rejects it before the evaluator is reached.

### 2. Silo deployment (one instance per tenant)

Service Policy runs as **separate deployments per tenant** sharing a single codebase. A deployment
for institution A has no visibility into the data or policies of institution B.

This model is chosen over a shared multi-tenant instance because:
- It eliminates the risk of cross-tenant data leakage at the application layer.
- Each institution can be patched, upgraded, or rolled back independently.
- Configuration surface per deployment is small (one `application.yml` overlay per tenant).
- Multitenancy machinery (row-level security, tenant-scoped caches, tenant-aware metrics) is
deferred until a second real tenant exists and its requirements are understood.

### 3. Rules as versioned configuration

Authorization policies (PDP rules) and supporting DMN decision tables are **versioned
configuration**, not code. They are delivered as a **baseline** (defaults committed to the repo)
plus per-tenant **overlays** applied at deployment time. This model allows:
- Policy changes to be reviewed via pull requests, with diff and history.
- Rollback by reverting to a previous config version.
- Institution-specific extensions without forking the codebase.

The baseline + overlay mechanism is a Phase 2 detail; the Phase 1.5 stub uses a flat
`application.yml` property list.

### 4. CURP as the canonical person identifier

**CURP** (Clave Única de Registro de Población) is stored as a standard Keycloak user attribute
from day one. It is the correlation key across systems for any natural person.

If a future requirement involves looking up the same person across two institution databases, the
lookup is done at the IdP layer (Keycloak federation or attribute enrichment), not inside the PDP.
The PDP receives CURP as a JWT claim and uses it in policy conditions per ADR-005. Merging user
stores is explicitly out of scope for Service Policy.

---

## Multitenancy deferral

Full multitenancy machinery (a single instance serving N tenants with per-tenant policy isolation)
is deferred until a second real tenant is onboarded and its isolation requirements are confirmed.
Until then, the silo model delivers equivalent isolation with zero added complexity.

---

## Consequences

**Positive:**
- Security boundary is enforced by Keycloak's JWT signature; no application-level tenant filtering
needed.
- Deployments are independently scalable, patchable, and monitored.
- Policy history and audit trail are per-tenant by construction (separate Git config branches or
overlays).
- CURP-based identity linking is ready from day one; a future cross-tenant lookup requires only an
IdP change, not a PDP change.

**Negative:**
- N tenants = N deployments to operate (N Helm releases, N monitoring targets).
- Configuration overlay mechanism must be designed carefully in Phase 2 to avoid per-tenant forks
of the codebase.
- A shared reporting dashboard across tenants requires an aggregation layer outside the PDP.
