# Architecture Decision Records

Architectural decisions for **service-policy** (ABAC Policy Decision Point).
Each record follows `templates/adr-template.md`. Decisions are immutable: a
superseded decision is not edited in place — a new ADR revises or replaces it and
both are kept for the audit trail.

**Status legend:** Proposed · Accepted · Superseded · Deprecated

|                       #                        |                                         Decision                                         |  Status  |
|------------------------------------------------|------------------------------------------------------------------------------------------|----------|
| [001](001-dedicated-abac-pdp.md)               | Dedicated ABAC Policy Decision Point                                                     | Accepted |
| [002](002-quarkus-java21.md)                   | Quarkus 3.x LTS + Java 21                                                                | Accepted |
| [003](003-authentication-oidc-jwt-jwks.md)     | Authentication input — OIDC/JWT validated via JWKS (IdP-agnostic; Keycloak as reference) | Accepted |
| [004](004-pep-contract-surface-and-stub.md)    | PEP contract surface and stub evaluator (Phase 1.5)                                      | Accepted |
| [005](005-attribute-id-keying.md)              | Policy attributes key on stable id/code, never display text                              | Accepted |
| [006](006-tenancy-model.md)                    | Tenancy model — tenant = institution = realm; silo deployment; rules as config           | Accepted |
| [007](007-sonarqube-cloud-public-repo.md)      | SonarQube Cloud for the public repository                                                | Accepted |
| [008](008-mvp-policy-domain.md)                | MVP policy domain                                                                        | Accepted |
| [009](009-test-coverage-tooling.md)            | Test coverage tooling and CDI scope convention                                           | Accepted |
| [010](010-subject-attribute-provenance.md)     | Subject attribute provenance: caller-asserted, behind a port                             | Accepted |
| [011](011-null-operand-semantics.md)           | Null operand semantics in condition comparisons                                          | Accepted |
| [012](012-policy-authoring-contract.md)        | Policy authoring contract (`POST /v1/policies`)                                          | Accepted |
| [013](013-pdp-endpoint-authorization.md)       | PDP endpoint authorization and subject provenance                                        | Accepted |
| [014](014-policy-lifecycle-crud-contract.md)   | Policy lifecycle and CRUD contract (`/v1/policies`)                                      | Accepted |
| [015](015-openapi-not-versioned.md)            | Do not version the generated OpenAPI specification                                       | Accepted |
| [016](016-head-pointer-activation.md)          | Head-pointer activation model                                                            | Accepted |
| [017](017-rest-response-contract.md)           | REST response contract — collection envelope, pagination, error shape                    | Accepted |
| [018](018-error-response-contract.md)          | Error response contract — RFC 9457 problem+json, conditional writes (If-Match/ETag)      | Accepted |
| [019](019-transaction-free-write-atomicity.md) | Transaction-free write atomicity — commit-point + self-healing                           | Accepted |
| [020](020-activation-write-path.md)            | Activation write-path — explicit-version activate + deactivate, conditional single-doc   | Accepted |
| [021](021-evaluator-cutover-head-pointer.md)   | Evaluator cutover to the head-pointer model; legacy single-collection path removed       | Accepted |
| [022](022-quarkus-jacoco-coverage.md)          | Adopt quarkus-jacoco for coverage instrumentation (revisits ADR-009)                     | Accepted |

## Relationships

- **ADR-016 revises ADR-014 §7.** The policy activation mechanism moved from a
  transactional `active` flag (two-document write, replica set required) to a
  denormalized head-pointer (single-document atomic write, standalone Mongo). The
  rest of ADR-014's lifecycle/CRUD contract stands.
- **ADR-018 revises ADR-014 and ADR-017.** The optimistic-concurrency transport
  moved from a body-carried `baseVersion` (→ 409) to conditional requests via
  `If-Match`/ETag = `revision` (→ 412/428), and the flat error shape was replaced by
  RFC 9457 `application/problem+json` across the whole error surface.
- **ADR-019 refines ADR-016.** ADR-016 made activation a single-document write;
  ADR-019 extends the same "single commit point + idempotent writes" principle to
  create (head-first) and append (compare-and-set on `revision`), which each touch
  two documents, so standalone Mongo stays sufficient for writes too.
- **ADR-020 implements the activation write-path anticipated by ADR-016.** ADR-016
  modeled activation as a single-document head write but left the store method and
  endpoints unbuilt; ADR-020 adds explicit-version `activate` and `deactivate` as
  admin-gated conditional writes (ADR-018) reusing the ADR-019 CAS pattern, and
  settles that only activation writes `activeContent`.
- **ADR-021 completes the ADR-016 migration.** ADR-016 moved writes and activation
  onto the head-pointer model but left evaluation reading the legacy `policies`
  collection; ADR-021 cuts the evaluator over to active heads and removes the legacy
  single-collection path, closing the ADR-019 Option A transitional window.
- **ADR-022 revisits ADR-009.** The standard JaCoCo agent could not measure
  Quarkus-augmented Panache repositories (0% despite tests); ADR-022 adopts the
  quarkus-jacoco extension as the agent. ADR-009's coverage rationale for the
  `@Singleton` convention is superseded, but the convention is retained on the
  CDI-proxy argument.
- **ADR-004** defined the Phase 1.5 PEP contract surface with a stub evaluator;
  the persistent evaluator that replaced the stub is covered by ADR-008 and
  ADR-010.
- **ADR-017** sets the REST response contract (collection envelope, pagination,
  error shape) for the read/list surface introduced by ADR-014's lifecycle.

## Notes

- ADRs are numbered per repository; numbers are not globally unique across the
  portfolio's repos.
- The generated OpenAPI specification is **not** versioned (ADR-015); it is served
  at `/q/openapi` and written to the build output.

