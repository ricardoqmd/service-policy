# Architecture Decision Records

Architectural decisions for **service-policy** (ABAC Policy Decision Point).
Each record follows `templates/adr-template.md`. Decisions are immutable: a
superseded decision is not edited in place — a new ADR revises or replaces it and
both are kept for the audit trail.

**Status legend:** Proposed · Accepted · Superseded · Deprecated

|                       #                        |                                          Decision                                          |  Status  |
|------------------------------------------------|--------------------------------------------------------------------------------------------|----------|
| [001](001-dedicated-abac-pdp.md)               | Dedicated ABAC Policy Decision Point                                                       | Accepted |
| [002](002-quarkus-java21.md)                   | Quarkus 3.x LTS + Java 21                                                                  | Accepted |
| [003](003-authentication-oidc-jwt-jwks.md)     | Authentication input — OIDC/JWT validated via JWKS (IdP-agnostic; Keycloak as reference)   | Accepted |
| [004](004-pep-contract-surface-and-stub.md)    | PEP contract surface and stub evaluator (Phase 1.5)                                        | Accepted |
| [005](005-attribute-id-keying.md)              | Policy attributes key on stable id/code, never display text                                | Accepted |
| [006](006-tenancy-model.md)                    | Tenancy model — tenant = institution = realm; silo deployment; rules as config             | Accepted |
| [007](007-sonarqube-cloud-public-repo.md)      | SonarQube Cloud for the public repository                                                  | Accepted |
| [008](008-mvp-policy-domain.md)                | MVP policy domain                                                                          | Accepted |
| [009](009-test-coverage-tooling.md)            | Test coverage tooling and CDI scope convention                                             | Accepted |
| [010](010-subject-attribute-provenance.md)     | Subject attribute provenance: caller-asserted, behind a port                               | Accepted |
| [011](011-null-operand-semantics.md)           | Null operand semantics in condition comparisons                                            | Accepted |
| [012](012-policy-authoring-contract.md)        | Policy authoring contract (`POST /v1/policies`)                                            | Accepted |
| [013](013-pdp-endpoint-authorization.md)       | PDP endpoint authorization and subject provenance                                          | Accepted |
| [014](014-policy-lifecycle-crud-contract.md)   | Policy lifecycle and CRUD contract (`/v1/policies`)                                        | Accepted |
| [015](015-openapi-not-versioned.md)            | Do not version the generated OpenAPI specification                                         | Accepted |
| [016](016-head-pointer-activation.md)          | Head-pointer activation model                                                              | Accepted |
| [017](017-rest-response-contract.md)           | REST response contract — collection envelope, pagination, error shape                      | Accepted |
| [018](018-error-response-contract.md)          | Error response contract — RFC 9457 problem+json, conditional writes (If-Match/ETag)        | Accepted |
| [019](019-transaction-free-write-atomicity.md) | Transaction-free write atomicity — commit-point + self-healing                             | Accepted |
| [020](020-activation-write-path.md)            | Activation write-path — explicit-version activate + deactivate, conditional single-doc     | Accepted |
| [021](021-evaluator-cutover-head-pointer.md)   | Evaluator cutover to the head-pointer model; legacy single-collection path removed         | Accepted |
| [022](022-quarkus-jacoco-coverage.md)          | Adopt quarkus-jacoco for coverage instrumentation (revisits ADR-009)                       | Accepted |
| [023](023-operand-type-validation.md)          | Operand type validation — reject literal mistyping at authoring, deny at evaluation        | Accepted |
| [024](024-application-scoping.md)              | Application scoping — `app` as a first-class policy dimension (policy, request, selection) | Accepted |
| [025](025-policy-list-status-filter.md)        | Policy listing shows all lifecycle states — `?status=` filter (default `all`)              | Accepted |
| [026](026-composite-policy-identity.md)        | Composite identity (app, policyId) + app-nested routes; app leaves request bodies          | Accepted |
| [027](027-policy-simulation.md)                | Policy simulation — dry-run evaluation against an unsaved document (admin-gated)           | Accepted |
| [028](028-action-catalogue.md)                 | Action catalogue per (app, resourceType); `*` expanded at authoring, never stored          | Accepted |
| [029](029-per-app-configuration.md)            | Per-application configuration as administrable data, not deployment config                 | Accepted |
| [030](030-permission-enumeration.md)           | Permission enumeration — three-valued evaluation without an instance (advisory)            | Accepted |


## Relationships

- **ADR-016 revises ADR-014 §7.** Activation moved from a transactional `active`
  flag (two-document write, replica set) to a denormalized head-pointer
  (single-document atomic write, standalone Mongo).
- **ADR-018 revises ADR-014 and ADR-017.** Optimistic concurrency moved from
  body-carried `baseVersion` (→409) to `If-Match`/ETag (→412/428), and the flat
  error shape became RFC 9457 `application/problem+json`.
- **ADR-019 refines ADR-016.** Extends "single commit point + idempotent writes" to
  create (head-first) and append (compare-and-set on `revision`).
- **ADR-020 implements the activation write-path** anticipated by ADR-016:
  explicit-version `activate`/`deactivate` as admin-gated conditional writes.
- **ADR-021 completes the ADR-016 migration.** Evaluator reads active heads;
  legacy single-collection path removed.
- **ADR-022 revisits ADR-009.** Adopts quarkus-jacoco so Quarkus-augmented Panache
  repositories are measured; `@Singleton` convention retained on the CDI-proxy
  argument.
- **ADR-023 refines ADR-011.** Extends the fail-safe operand rule: literal type
  errors are rejected at authoring (`INVALID_POLICY`); runtime attribute type
  mismatches deny (like an absent operand), so `/evaluate` never returns 500 on
  operand type.
- **ADR-004** defined the Phase 1.5 PEP contract surface with a stub evaluator;
  the persistent evaluator is covered by ADR-008 and ADR-010.
- **ADR-017** sets the REST response contract for the read/list surface.
- **ADR-024 refines ADR-006 and ADR-008.** ADR-006 scopes tenancy at the institution
  level (one silo per institution); ADR-024 adds the intra-tenant dimension: `app` is
  a required policy field, carried on the evaluation request, and part of policy
  selection `(app, resourceType, verb)`. Cross-application isolation becomes an engine
  guarantee rather than a naming convention, and `resourceType` becomes a per-app
  namespace. Per-app administrative scope stays outside the engine as a subject
  attribute (ADR-010), evaluated as a meta-policy.
- **ADR-026 completes ADR-024.** ADR-024 made `app` the scoping dimension but left
  `policyId` globally unique, forcing applications to coordinate policy names — and
  producing an error that misdescribed a legitimate create as an attempt to mutate
  another app's policy. ADR-026 makes identity composite `(app, policyId)` and moves the
  whole v1 surface under `/v1/apps/{app}/…`, so the path is the single source of scope;
  `app` leaves every request body. A single read-only cross-app catalogue
  (`GET /v1/policies`) survives for multi-app administrators.
- **ADR-028 refines ADR-008 and ADR-012.** Actions gain a declared vocabulary per
  `(app, resourceType)`, administered through the admin surface rather than deployment
  config. `*` becomes input sugar expanded at authoring against that catalogue and never
  stored, which removes two problems: policies written with `*` no longer widen silently
  when a verb is added later, and the set of `(resourceType, action)` pairs becomes
  enumerable — a precondition for the permissions surface.
- **ADR-029 refines ADR-013 and completes ADR-024/ADR-026.** Applications became
  structural, but their configuration would still have lived in `application.properties`,
  making onboarding an application a redeploy and every change restart the engine for all
  of them. Per-application settings (claim-to-attribute mapping, attribute-source details)
  move to MongoDB and are administered through the admin surface with the same gate and the
  same `If-Match` contract as policy heads. ADR-013's global markers stay in properties:
  global and deployment-shaped stays in config, per-application becomes data.

## Notes

- ADRs are numbered per repository; numbers are not globally unique across the
  portfolio's repos.
- The generated OpenAPI specification is **not** versioned (ADR-015).

