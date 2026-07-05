# 13. PDP endpoint authorization and subject provenance

Date: 2026-07-02 (updated 2026-07-05: authorization-marker representation finalized — mode-based, alternative B)

## Status

Accepted

Refines ADR-004 (PEP contract surface) and ADR-010 (subject provenance) without
renumbering them. Supersedes the stub authentication introduced in ADR-004.

**Scope boundary.** This ADR governs how the PDP *validates* an incoming token and
authorizes the request. How a PEP *obtains* its token from the IdP (client_secret
vs. private_key_jwt vs. mTLS) is a separate concern, out of scope here, to be
settled in an auth/department ADR. The two are independent layers: the access token
reaching the PDP is identical regardless of the client-authentication method used
to mint it.

## Context

Phase 3 replaces the stub `SubjectResolver` — which base64-decodes the JWT payload
**without verifying the signature** — with real OIDC/JWKS validation. This forces
several authorization questions to be settled at once:

- The service exposes two endpoint classes: a **control plane**
  (`/v1/policies*`, policy authoring and the upcoming CRUD) and a **data plane**
  (`/v1/evaluate`, `/v1/evaluate/batch`, `/v1/permissions`).
- ADR-010 fixed **subject-from-Bearer**: the subject is derived from the validated
  token, never sent by the caller. This is correct for the two dominant cases — a
  user asking about their own permissions to build a UI menu, and a PEP enforcing
  the current user's action — but it cannot express a **delegated** query, where a
  backend service asks about the permissions of an arbitrary user `X` and the
  caller (the service) is not the subject.
- The service is a public, reusable PDP intended to run against multiple identity
  providers. It must not hardcode an IdP, a claim location, or role names — and the
  authority marker that grants admin/delegation is a **role** in Keycloak but a
  **scope** in Auth0/Okta (M2M tokens have no user, so roles are user-centric there).

## Decision

**1. Token validation.** Adopt `quarkus-oidc` as a bearer-only resource server
(`quarkus.oidc.application-type=service`): validates signature via the JWKS
endpoint, plus `iss`, `exp`, and `aud`. Subject identity is the validated `sub`
(fallback `preferred_username`, per ADR-010). The `SubjectResolver` stub and its
test are removed.

**2. Configurable authorization markers (mode-based).** Both gating markers — the
admin marker (control plane) and the delegation marker (data plane) — are
configured with an **explicit mode**, so a consumer on any IdP maps them to a role
or a scope without code changes:

```
service-policy.authz.admin.mode        = role | scope
service-policy.authz.admin.role        = authz-admin        (used when mode=role)
service-policy.authz.admin.scope       = ...                (used when mode=scope)
service-policy.authz.delegation.mode   = role | scope
service-policy.authz.delegation.role   = pdp-client         (used when mode=role)
service-policy.authz.delegation.scope  = ...                (used when mode=scope)
```

- `mode=role` → leans on the quarkus-oidc native role mapping
  (`quarkus.oidc.roles.role-claim-path`, e.g. `realm_access/roles` for Keycloak);
  the check is `SecurityIdentity.hasRole(configuredRole)`.
- `mode=scope` → reads the standard OIDC `scope` claim, splits on whitespace, and
  checks membership.
- Authorization is enforced with **programmatic checks**, not `@RolesAllowed`
  (whose argument must be a compile-time constant, incompatible with configurable
  names/modes).

**3. Mandatory startup validation (fail-fast).** A `StartupEvent` observer
validates each marker at boot. If the active mode's value is missing or blank, the
application **fails to start** with a clear message. If the inactive mode's value is
populated (config smell, e.g. a leftover `role` while `mode=scope`), it logs a
`WARN` and ignores it. Without this validation, alternative B is a footgun; it is
therefore part of the decision, not optional hardening.

**4. Control-plane authorization.** `/v1/policies*` (create today, full CRUD next)
require the admin marker; `403` otherwise.

**5. Data-plane subject provenance — hybrid rule.**
- Request carries **no explicit subject** → subject = `token.sub` (self). ADR-010
default, unchanged.
- Request carries an **explicit subject equal to `token.sub`** → allowed.
- Request carries an **explicit subject `≠ token.sub`** → require the delegation
marker; `403` otherwise.

**6. Concrete surface.** `EvaluationRequest` gains an **optional** `subject` field;
absent means self (backwards compatible). The explicit subject travels in the
**POST body** (never the URL/query string) and carries the opaque Keycloak `sub`
(ADR-005). `/v1/permissions` stays GET/self-only for now; its **delegated**
transport is deferred to the bulk-permissions decision.

## Reasons

- **Hybrid subject rule** is a strict superset of subject-from-Bearer: self-service
  keeps working with only the Bearer; delegated queries become possible **without
  token-exchange**. Anti-spoofing is preserved by gating explicit-subject-`≠`-self
  on the delegation marker, not by forbidding the field.
- **Mode-based markers (B) over a generic claim+value mechanism (A):** both cover
  the same consumer cases, and A is the more stable, less-code option. B is chosen
  deliberately to seed an **explicit extension seam** for a platform expected to run
  against multiple IdPs from day one, and for readability of the YAML (it states
  "this is a role" / "this is a scope"). The cost — an enum, a switch, and
  mandatory startup validation — is accepted now rather than deferred.
- **Configurable markers + claim path** keep identity/validation on standard OIDC
  while acknowledging that authorization markers are an application convention OIDC
  does not standardize.
- **No revert:** ADR-010's behavior is exactly the "subject omitted → self" branch.

## Alternatives considered

**A) Generic claim+value marker (single mechanism).** One pair per marker —
`delegation-claim` (a claim path) + `delegation-value` — and the PDP checks "does
the configured claim contain the configured value?". A Keycloak role is a value in
`realm_access/roles`; a scope is a value in `scope`; to the PDP they are identical.

> **Best-option note (impact of choosing A instead).** A is the more stable, boring
> option: no enum, no switch, no per-mode validation, and it prevents
> mode-mismatch config errors *by construction* rather than by validation + docs.
> Its only shortfall vs. B is the lack of an explicit extension seam and slightly
> less self-describing YAML. Crucially, **migrating A → B later is additive and
> non-breaking** (add the mode, keep the generic path as default), so the cost of
> deferring B is near zero. A remains the recommendation on pure engineering
> grounds; B is chosen for the platform's stated multi-IdP readiness intent, with
> eyes open to the added surface. Named, not omitted.

**T) Textbook XACML — subject always explicit, caller always separately
authenticated.**

> **Best-option note.** The more orthodox XACML model (subject as a first-class
> parameter everywhere). For this system it buys nothing the hybrid rule does not
> already provide, while costing: every self-service call must self-declare its
> subject (friction + a `GET→POST` regression on `/permissions`); it breaks the
> ADR-004/ADR-010 frozen contract; and it still needs "self-only unless
>
>> delegation-marker" enforcement — which *is* the hybrid rule. Adopt only if a
>> second consumer requires a subject-parametric contract.

**X) Confidential client + client_credentials + token-exchange** to preserve the
end-user identity on service-to-service calls. Rejected for now: token-exchange is
heavier and more IdP-specific; the hybrid rule covers the delegated case with a
plain marker claim.

**G) Gate the entire data plane on the delegation marker.** Rejected: it breaks
self-service (every user would need the marker), defeating the endpoint's purpose.

## Consequences

- One coherent model covers self-service, enforcement, and delegated queries.
- IdP-agnostic; no identity-provider lock-in in code; role vs. scope is config.
- Backwards compatible with ADR-010; no contract revert.
- B introduces a mode enum + switch + **mandatory** startup validation; without the
  validation B would allow silent mode-mismatch misconfiguration.
- Removing the stub breaks the tests that hand-build unverified JWTs
  (`SubjectResolverTest` and the resource tests); they move to minted/`@TestSecurity`
  tokens.
- Consumers provision two markers in their IdP (roles or scopes) and set the modes;
  documented in the README.
- UI permissions from `/v1/permissions` remain a UX convenience, **not** an
  enforcement boundary — backends must still enforce via `/v1/evaluate`.

## Revisit if

- No second IdP or marker-type-dependent semantics ever materializes → B's seam
  stays unused (cheap; acceptable). Consider collapsing to A only if the enum proves
  to be pure overhead.
- A second consumer needs a subject-parametric (textbook) contract → promote
  alternative T.
- Service-to-service calls must preserve **both** caller and end-user identity at
  once → revisit token-exchange (alternative X).
- The delegated `/v1/permissions` transport is needed → settle it in the
  bulk-permissions ADR (query-param vs. POST, sub-in-URL trade-off).

