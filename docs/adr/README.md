# Architecture Decision Records

Architectural decisions for **service-policy** (ABAC Policy Decision Point).
Each record follows `templates/adr-template.md`. Decisions are immutable: a
superseded decision is not edited in place — a new ADR revises or replaces it and
both are kept for the audit trail.

**Status legend:** Proposed · Accepted · Superseded · Deprecated

|                      #                       |                                         Decision                                         |  Status  |
|----------------------------------------------|------------------------------------------------------------------------------------------|----------|
| [001](001-dedicated-abac-pdp.md)             | Dedicated ABAC Policy Decision Point                                                     | Accepted |
| [002](002-quarkus-java21.md)                 | Quarkus 3.x LTS + Java 21                                                                | Accepted |
| [003](003-authentication-oidc-jwt-jwks.md)   | Authentication input — OIDC/JWT validated via JWKS (IdP-agnostic; Keycloak as reference) | Accepted |
| [004](004-pep-contract-surface-and-stub.md)  | PEP contract surface and stub evaluator (Phase 1.5)                                      | Accepted |
| [005](005-attribute-id-keying.md)            | Policy attributes key on stable id/code, never display text                              | Accepted |
| [006](006-tenancy-model.md)                  | Tenancy model — tenant = institution = realm; silo deployment; rules as config           | Accepted |
| [007](007-sonarqube-cloud-public-repo.md)    | SonarQube Cloud for the public repository                                                | Accepted |
| [008](008-mvp-policy-domain.md)              | MVP policy domain                                                                        | Accepted |
| [009](009-test-coverage-tooling.md)          | Test coverage tooling and CDI scope convention                                           | Accepted |
| [010](010-subject-attribute-provenance.md)   | Subject attribute provenance: caller-asserted, behind a port                             | Accepted |
| [011](011-null-operand-semantics.md)         | Null operand semantics in condition comparisons                                          | Accepted |
| [012](012-policy-authoring-contract.md)      | Policy authoring contract (`POST /v1/policies`)                                          | Accepted |
| [013](013-pdp-endpoint-authorization.md)     | PDP endpoint authorization and subject provenance                                        | Accepted |
| [014](014-policy-lifecycle-crud-contract.md) | Policy lifecycle and CRUD contract (`/v1/policies`)                                      | Accepted |
| [015](015-openapi-not-versioned.md)          | Do not version the generated OpenAPI specification                                       | Accepted |
| [016](016-head-pointer-activation.md)        | Head-pointer activation model                                                            | Accepted |

## Relationships

- **ADR-016 revises ADR-014 §7.** The policy activation mechanism moved from a
  transactional `active` flag (two-document write, replica set required) to a
  denormalized head-pointer (single-document atomic write, standalone Mongo). The
  rest of ADR-014's lifecycle/CRUD contract stands.
- **ADR-004** defined the Phase 1.5 PEP contract surface with a stub evaluator;
  the persistent evaluator that replaced the stub is covered by ADR-008 and
  ADR-010.

## Notes

- ADRs are numbered per repository; numbers are not globally unique across the
  portfolio's repos.
- The generated OpenAPI specification is **not** versioned (ADR-015); it is served
  at `/q/openapi` and written to the build output.

