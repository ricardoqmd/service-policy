# ADR-003: Authentication Input — OIDC/JWT Validated via JWKS (IdP-Agnostic; Keycloak as Reference)

| Field  |             Value             |
|--------|-------------------------------|
| Status | Accepted                      |
| Date   | 2026-05-19                    |
| Author | Ricardo Quintero Mármol Durán |

---

## Context

Every request to the PDP must be authenticated: the service needs to know who the subject is before
it can evaluate an authorization policy on their behalf. The PDP also must not become a session
store or an identity provider — those concerns belong to a dedicated IdP (Keycloak in this
platform).

The PDP's role is narrow: **extract a verified subject identifier from the inbound request** and
pass it to the evaluator.

Several questions must be answered:

1. What credential format should PEPs use?
2. How does the PDP verify the credential?
3. Which claim provides the subject identifier?
4. Should the PDP accept tenant or subject identifiers from the request body?

---

## Decision

PEPs authenticate requests using a **JWT Bearer token** in the `Authorization: Bearer <token>`
HTTP header. The PDP validates the token signature against the **JWKS endpoint** of the
configured identity provider.

### Why JWT + JWKS

- **Stateless.** The PDP does not maintain sessions or call the IdP per request; it fetches the
  JWKS once (or on key rotation) and verifies signatures locally.
- **IdP-agnostic.** Any OIDC-compliant IdP exposes a JWKS endpoint. Keycloak is the reference
  implementation but can be replaced without changing the PDP or its PEP clients.
- **Standard.** OAuth 2.0 Bearer tokens (RFC 6750) are understood by every API gateway, service
  mesh, and client library on the platform.

### Subject claim priority

The subject identifier is extracted from the JWT in this order:

1. `sub` claim (stable, unique identifier assigned by the IdP).
2. `preferred_username` claim (fallback for IdPs that populate this instead of `sub`).
3. `"unknown"` (sentinel; policies should not grant access to unknown subjects).

The subject is **never** accepted from the request body or a query parameter. A JWT that does not
contain a usable subject claim is treated as unauthenticated.

### Tenant from issuer

The `iss` (issuer) claim identifies the Keycloak realm, which equals the tenant. See ADR-006 for
the full tenancy model.

### IdP reference implementation

Keycloak is used as the reference IdP. The JWKS URL follows the standard Keycloak path:
`<realm-url>/protocol/openid-connect/certs`. No Keycloak-specific claim names are required beyond
the standard OIDC claims (`sub`, `iss`, `preferred_username`).

### Phase 1.5 (stub) vs. Phase 3 (production)

**Phase 1.5 (current):** The `SubjectResolver` class decodes the JWT payload via base64url without
verifying the signature. This is intentional and documented; the service MUST NOT be deployed to
any networked environment in this state. The stub is removed in Phase 3.

**Phase 3:** `quarkus-oidc` is introduced. It handles JWKS fetch, key caching, signature
verification, and claim extraction. `SubjectResolver` is replaced or reduced to a thin wrapper.

---

## Alternatives considered

### API key / HMAC per service

Simpler for internal service-to-service calls. Rejected: keys are long-lived secrets requiring
rotation infrastructure; they do not carry user identity for audit; they do not integrate with the
existing Keycloak-based SSO session.

### mTLS client certificates

Strong authentication without tokens. Rejected for Phase 1: certificates require PKI
infrastructure not currently in scope. Orthogonal to JWT — can be added as an additional layer
later.

### Accept subject from the request body

Would allow PEPs that do not forward the original user JWT (e.g. backend-to-backend calls). Rejected:
it shifts the authentication decision to the PEP, undermining the PDP's ability to verify identity.
Any backend service could claim to act as any subject. PEPs must propagate the original user JWT
or obtain a service account JWT and use their own `sub`.

### Opaque tokens + introspection endpoint

Standard OAuth 2.0 introspection (RFC 7662). Rejected: requires a network call to the IdP on
every request, adding latency and a failure mode. JWKS-based verification is local after the
initial key fetch.

---

## Consequences

**Positive:**
- PEPs use an existing, already-issued JWT — no additional credential management.
- Stateless verification: no per-request IdP call after JWKS fetch.
- Replacing Keycloak with another OIDC-compliant IdP requires only a JWKS URL change.
- The `sub` claim is stable across token refreshes; audit logs remain correlated.

**Negative:**
- JWTs have a fixed expiry; a revoked token remains valid until expiry (standard JWT limitation).
Mitigated by short access token TTLs configured in Keycloak.
- JWKS key rotation requires the PDP to detect and refresh the key set. `quarkus-oidc` handles
this transparently in Phase 3.
