# 29. Per-application configuration as administrable data, not deployment config

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Ricardo
- **Refines:** ADR-013 (configurable markers), ADR-024/ADR-026 (applications are first-class)
- **Enables:** claim-to-attribute mapping and the PIP adapter (their own ADRs)

## Context

ADR-024 and ADR-026 made the application a first-class, structural dimension: policies are
identified by `(app, policyId)` and the whole surface is nested under `/v1/apps/{app}/…`.
But everything an application needs *configured* — which token claims map to which subject
attributes, where its attribute source lives — would naturally land in
`application.properties`, following the pattern of ADR-013's configurable markers.

That pattern does not survive the multi-application model. **Onboarding an application
would require redeploying the engine**, and every configuration change to one application
would restart the service for all of them. A PDP whose purpose is to serve several
applications cannot make adding one an operations event.

ADR-013's approach was right for what it configured: a single, global, deployment-level
marker (which role or scope means "admin"). What is arriving now is different — per-app,
tenant-shaped, changing at the cadence of applications joining and evolving.

## Decision

**Per-application configuration is data, stored in MongoDB and administered through the
PDP's admin surface** (and therefore by the PAP). Global, deployment-level settings stay in
`application.properties`.

### Shape

One configuration document per application, holding the settings that later ADRs populate:

```jsonc
{
  "app": "kronia",
  "subjectAttributes": {                 // attribute name → claim path
    "rol": "resource_access.kronia.roles"
  },
  "pip": {                               // attribute source for what the token lacks
    "url": "https://kronia-backend/api/subjects/{sub}/attributes",
    "timeoutMs": 500,
    "cacheTtlSeconds": 300,
    "credentialRef": "kronia-pip"        // REFERENCE, never the secret itself
  },
  "revision": 3
}
```

- Admin-gated (ADR-013), addressed under `/v1/apps/{app}/…` (ADR-026).
- **`revision`** follows the same optimistic-concurrency contract as policy heads
  (ADR-018): `If-Match`/ETag, 412 on stale, 428 when missing. Configuration is edited by the
  same administrators, through the same control plane; it gets the same protection.

### Secrets are referenced, never stored

Credentials for calling an attribute source are **not** written into the configuration
document. The document holds a `credentialRef`; the actual secret is resolved from the
deployment's secret mechanism (properties/environment/secret store). A configuration
collection an administrator can read must never become a place where credentials live.

### Absent configuration degrades, it does not deny

An application with no configuration document behaves exactly as the engine behaves today:
subject attributes come only from the caller (ADR-010), no claim mapping, no attribute
source. Evaluation is unaffected — policies still evaluate against asserted attributes.

This is deliberate and is *not* a fail-safe exception: configuration is **additive
capability**, not a permission. Its absence removes the engine's ability to *derive*
attributes, which makes fewer things determinable, never more permitted. Under
deny-overrides with `defaultEffect: DENY`, fewer resolved attributes can only produce equal
or stricter outcomes (ADR-011). Nothing widens.

### Reading it is cached, with revision-based invalidation

The evaluator cannot read Mongo on every decision. Configuration is cached in memory and
invalidated by `revision`; a write bumps the revision and the cache refreshes. The
cache-refresh strategy (TTL versus explicit invalidation) is an implementation matter, but
the invariant is fixed here: **a decision must never be made with configuration known to be
stale beyond the refresh window**, and the window must be documented.

### Validated on write

A configuration document is validated when written, not when first used: known keys, URL
syntax, timeout and TTL within bounds. Attempting to *reach* the configured source at write
time is deliberately excluded — a source that is down at configuration time is not a
configuration error, and a write path that performs outbound calls is a write path that can
hang.

## Reasons

- **Adding an application must not be a deploy.** This is the whole point of the
  multi-application model; configuration was the last piece still tied to the artifact.
- **It matches what the PAP already is.** The control plane administers policies through the
  PDP's API; per-app configuration is administered the same way, by the same people, with
  the same gate and the same concurrency contract.
- **Blast radius.** Deployment configuration is global: an edit for one application restarts
  the engine for all. Data configuration is scoped to its document.
- **The precedent is intact, not contradicted.** ADR-013 configured a global,
  deployment-level concern in properties, and it stays there. What moves to data is what is
  per-application by nature.

## Alternatives considered

- **Keep it in `application.properties`, keyed by app** (`authz.apps.kronia.…`). Zero new
  surface, consistent with ADR-013. Rejected: onboarding an application becomes a redeploy,
  and one application's change restarts every application's engine.
- **A mounted configuration file reloaded at runtime** (a ConfigMap or watched file). Avoids
  the redeploy without a new API. Rejected: it makes the PAP unable to administer it (the
  control plane would have to write files into the runtime environment), and it puts
  configuration outside the audit trail that every other administrative change already has.
- **A separate configuration service.** Correct in a larger platform. Rejected here: it adds
  a component and a failure mode for a handful of documents that the PDP already has a
  database and an admin surface for.
- **Fold the action catalogue (ADR-028) into this document.** Rejected there and here: the
  catalogue is authored as the application's domain evolves, this is set by an operator when
  the application is onboarded; separate resources keep permissions and history separate.

### Better option not omitted (and its impact)

- **Version the configuration the way policies are versioned** — append-only history plus
  explicit activation, so a configuration change can be reviewed before taking effect and
  rolled back afterwards. Impact: genuinely valuable, because a bad claim mapping or a wrong
  attribute-source URL silently changes what the engine can determine, and today the only
  trace would be the current document and its audit metadata. It is deferred because the
  full lifecycle (versions, activation, head pointer) is a large amount of machinery for a
  document that changes rarely, and because the mutable-with-audit form is enough to detect
  and correct a mistake. Trigger: a configuration change causes an incident that the audit
  metadata alone cannot explain, or a review gate is required before configuration takes
  effect.

## Consequences

- New collection and a new admin-gated configuration resource under `/v1/apps/{app}/…`.
- The engine gains a configuration cache on the evaluation path, with revision-based
  invalidation and a documented staleness window.
- Applications can be onboarded and reconfigured with no deploy.
- Configuration changes carry audit metadata (who, when) like every other administrative
  write, and are protected by `If-Match`.
- Secrets remain outside the database; only references are stored, so the deployment still
  owns credential material.
- ADR-013's global markers stay in `application.properties`; the two mechanisms coexist with
  a clear rule — global and deployment-shaped stays in properties, per-application becomes
  data.

## Criteria to revisit

- A configuration mistake causes an incident that audit metadata cannot explain, or changes
  need review before taking effect → version the configuration (the deferred option above).
- The number of applications or the read rate makes the cache the bottleneck → revisit the
  invalidation strategy (push instead of revision polling).
- Configuration grows beyond the engine's own concerns (becoming a general application
  registry for the ecosystem) → that belongs in a platform service, not in the PDP.

