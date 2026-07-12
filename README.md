[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ricardoqmd_service-policy&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ricardoqmd_service-policy)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=ricardoqmd_service-policy&metric=coverage)](https://sonarcloud.io/summary/new_code?id=ricardoqmd_service-policy)
[![CI](https://github.com/ricardoqmd/service-policy/actions/workflows/ci.yml/badge.svg)](https://github.com/ricardoqmd/service-policy/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.37%20LTS-blueviolet.svg)](https://quarkus.io/)
[![MongoDB](https://img.shields.io/badge/MongoDB-7-green.svg)](https://www.mongodb.com/)
[![Conventional Commits](https://img.shields.io/badge/Conventional%20Commits-1.0.0-yellow.svg)](https://conventionalcommits.org)
[![Status](https://img.shields.io/badge/status-alpha-red.svg)](#roadmap)

# Service Policy

> Open source ABAC Policy Decision Point (PDP) built with Quarkus.

Service Policy is a lightweight, stateless authorization engine that evaluates
fine-grained access control policies for distributed systems. It separates
authorization decisions from your application code, letting policies be edited,
versioned, and audited centrally — without redeploying services.

> **Status: early development (0.x).** API and policy schemas may change
> between minor versions. Not yet recommended for production use.

---

## Why use it

- **One place for authorization logic.** Stop duplicating `if (user.hasRole...)`
  checks across multiple services in different languages.
- **Edit policies without redeploying.** Policies are data, not code — update
  them at runtime through the admin API and decisions change immediately.
- **Audit every decision.** Compliance teams get a queryable log of every
  authorization decision ever made.
- **Multi-stack friendly.** Any service that can make an HTTP call can use it:
  Java, Python, PHP, Node.js, Go, .NET — all the same REST API.
- **Built for ops.** 12-factor by default. Stateless. Healthchecks. Prometheus
  metrics. Structured JSON logs. Kubernetes-ready when you need it.

---

## Quickstart

### Dev mode (no Docker required)

```bash
git clone https://github.com/ricardoqmd/service-policy.git
cd service-policy
./mvnw quarkus:dev
```

The service starts on `http://localhost:8080`. Live reload is enabled — edit
any source file and changes apply on the next request.

### Full stack with Docker Compose

```bash
# Copy environment template and edit as needed
cp .env.example .env

# Start MongoDB + Mongo Express + service-policy (JVM image)
docker compose up -d

# Or with the native image
docker compose -f docker-compose.native.yml up -d
```

### Access points

|    Service     |                 URL                  |             Notes             |
|----------------|--------------------------------------|-------------------------------|
| Service Policy | `http://localhost:8080`              | Main API                      |
| Swagger UI     | `http://localhost:8080/q/swagger-ui` | Dev mode only                 |
| Health         | `http://localhost:8080/q/health`     | Liveness + readiness          |
| Metrics        | `http://localhost:8080/q/metrics`    | Prometheus format             |
| Mongo Express  | `http://localhost:8081`              | MongoDB admin UI (local only) |

> Ports are configurable via `.env`. See [DOCKER.md](DOCKER.md) for the full
> Docker reference.

---

## Architecture

Service Policy implements the classic XACML-inspired three-component pattern:

- **Policy Decision Point (PDP)** — this service. Evaluates authorization queries
  and returns allow/deny decisions, and also exposes the full policy authoring API
  (create, version, activate, deactivate).
- **Policy Enforcement Point (PEP)** — your application, API gateway, or frontend.
  Calls this service and acts on the result.
- **Policy Administration Point (PAP)** — a separate control-plane application
  (UI + backend) that consumes the authoring API of this service. Planned as an
  independent component.

**Persistence model.** Policies are stored in MongoDB using a head-pointer model:
`policy_heads` holds the current active version pointer; `policy_versions` holds the
immutable append-only version history. Requires a standalone MongoDB instance (no
replica set needed for development).

**Hexagonal layering.** The codebase is divided into `domain` (pure Java — no
framework dependencies), `persistence` (MongoDB/Panache), `rest` (RESTEasy Reactive),
`evaluation`, `problem`, and `config`. Layering invariants are enforced at build time
by [ArchUnit](https://www.archunit.org/) rules in the test suite.

For the full architecture rationale, trade-offs, and query model see
[docs/architecture.md](docs/architecture.md).

---

## PEP contract (v1)

Three endpoints form the stable contract that Policy Enforcement Points integrate against.
The machine-readable spec is generated at runtime — `GET /q/openapi` (Swagger UI at
`/q/swagger-ui` in dev mode). It is deliberately **not** versioned in the repo
([ADR-015](docs/adr/015-openapi-not-versioned.md)).

| Method |         Path         |                      Description                       |
|--------|----------------------|--------------------------------------------------------|
| POST   | `/v1/evaluate`       | Single authorization request → `Decision` (allow/deny) |
| POST   | `/v1/evaluate/batch` | Batch of requests → list of `Decision`                 |
| GET    | `/v1/permissions`    | Cacheable flat permission list for a subject + `?app=` |

Runnable request/response examples:
[docs/http/evaluation-endpoints.http](docs/http/evaluation-endpoints.http).

### Authentication

All data-plane and control-plane endpoints require a valid Bearer JWT. The token is validated
via JWKS (signature, `iss`, `exp`, `aud`) using `quarkus-oidc` in bearer-only mode.

```
QUARKUS_OIDC_AUTH_SERVER_URL=https://your-idp/realms/default
QUARKUS_OIDC_TOKEN_AUDIENCE=service-policy    # must match the aud claim
```

Subject is resolved from the validated `sub` claim (fallback: `preferred_username`).
`/q/health` and `/info` are public. `GET /v1/permissions` responses are safe to cache
per `subject + app + policyVersion`.

### Authorization markers (ADR-013)

Two authorization markers gate elevated operations. Each marker has a configurable **mode**
(`role` or `scope`) so it maps cleanly to any IdP:

|   Marker   | Default mode | Default value |                 Controls                 |
|------------|--------------|---------------|------------------------------------------|
| admin      | `role`       | `authz-admin` | `POST /v1/policies` (control plane)      |
| delegation | `role`       | `pdp-client`  | Explicit `subject` ≠ caller (data plane) |

**mode=role** (Keycloak default): the check is `identity.hasRole(configuredRole)`.
The role claim location defaults to `realm_access/roles`; override for other IdPs:

```
QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH=roles          # Auth0 / Okta flat roles
```

**mode=scope** (Auth0 / Okta M2M tokens): the check splits the `scope` claim on whitespace
and checks membership:

```
SERVICE_POLICY_AUTHZ_ADMIN_MODE=scope
SERVICE_POLICY_AUTHZ_ADMIN_SCOPE=authz-admin
SERVICE_POLICY_AUTHZ_DELEGATION_MODE=scope
SERVICE_POLICY_AUTHZ_DELEGATION_SCOPE=pdp-client
```

The application **fails to start** if the active mode's value is missing or blank (fail-fast
startup validation). An orphan field (e.g., `role` set while `mode=scope`) logs a `WARN` and
is otherwise ignored.

### Delegated queries (hybrid subject rule, ADR-013 §5)

The `EvaluationRequest` body accepts an optional `subject` field for delegated queries:

- Absent or equal to the caller's own `sub` → self-service (backwards compatible).
- Different from the caller → requires the delegation marker; `403` otherwise.

```json
{ "action": "document:read",
  "resource": {"type": "document", "id": "d1"},
  "subject": "user-x" }
```

Relevant ADRs:
[ADR-003 — authentication / OIDC](docs/adr/003-authentication-oidc-jwt-jwks.md) ·
[ADR-004 — contract surface](docs/adr/004-pep-contract-surface-and-stub.md) ·
[ADR-005 — attribute id/code keying](docs/adr/005-attribute-id-keying.md) ·
[ADR-006 — tenancy model](docs/adr/006-tenancy-model.md) ·
[ADR-013 — PDP endpoint authorization](docs/adr/013-pdp-endpoint-authorization.md)

---

## Policy administration (v1)

All policy authoring and lifecycle endpoints require the admin marker (see
[Authorization markers](#authorization-markers-adr-013) above). Errors follow the
RFC 9457 `application/problem+json` contract — see [docs/ERRORS.md](docs/ERRORS.md)
for the full error catalog.

### Endpoints

| Method |                  Path                  |                       Description                       |
|--------|----------------------------------------|---------------------------------------------------------|
| POST   | `/v1/policies`                         | Create a policy (version 1, inactive)                   |
| PUT    | `/v1/policies/{id}`                    | Append version N+1 (conditional write)                  |
| POST   | `/v1/policies/{id}/activate`           | Activate a specific version (conditional write)         |
| POST   | `/v1/policies/{id}/deactivate`         | Soft-deactivate — history preserved (conditional write) |
| GET    | `/v1/policies`                         | List policy heads (paginated; `?view=full` for content) |
| GET    | `/v1/policies/{id}`                    | Get a policy head + `ETag`                              |
| GET    | `/v1/policies/{id}/versions`           | List all versions (paginated)                           |
| GET    | `/v1/policies/{id}/versions/{version}` | Get one immutable version                               |

### Policy lifecycle

```
CREATE (inactive v1) → APPEND (immutable v2, v3, …) → ACTIVATE (explicit) → DEACTIVATE (soft)
```

Key invariants:

- At most one version is active per policy at any time.
- Versions are immutable once appended; a change means appending a new version.
- Activation is always explicit: `POST /activate` with the target version number.
- Deactivating soft-retires a policy — all versions are preserved, nothing is deleted.
  A policy with no active version evaluates to the fail-safe default deny.

### Optimistic concurrency

Append, activate, and deactivate are conditional writes: they require an `If-Match`
header carrying the policy head's current `ETag`.

| Status |                              Meaning                              |
|--------|-------------------------------------------------------------------|
| `428`  | `If-Match` is absent — read the resource first to obtain the ETag |
| `412`  | ETag is stale — another write happened; reload and retry          |

### Example: create and activate a policy

```http
# 1) Create (inactive, version 1)
POST /v1/policies
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "policyId": "doc-access",
  "version": 1,
  "resourceType": "document",
  "actions": ["*"],
  "combiningAlgorithm": "DENY_OVERRIDES",
  "defaultEffect": "DENY",
  "rules": [
    { "id": "assigned-access", "effect": "PERMIT",
      "condition": { "type": "comparison", "op": "IN",
        "left": {"ref": "subject.id"}, "right": {"ref": "resource.attr.assignees"} } },
    { "id": "sealed-deny", "effect": "DENY",
      "condition": { "type": "comparison", "op": "EQ",
        "left": {"ref": "resource.attr.sealed"}, "right": {"value": true} } }
  ]
}
# → 201 Created  {"policyId":"doc-access","version":1,"active":false}

# 2) Read ETag from the head
GET /v1/policies/doc-access
# → 200 OK  ETag: "0"

# 3) Activate (policy is now evaluable)
POST /v1/policies/doc-access/activate
Authorization: Bearer <admin-token>
If-Match: "0"
Content-Type: application/json

{"version": 1}
# → 200 OK
```

Two runnable HTTP files (VS Code REST Client / IntelliJ HTTP Client) exercise the
full contract top-down:

- [docs/http/lifecycle-walkthrough.http](docs/http/lifecycle-walkthrough.http) —
  create → version → activate → evaluate → deactivate, including every
  RFC 9457 error shape (409/412/428/404/422/403).
- [docs/http/evaluation-endpoints.http](docs/http/evaluation-endpoints.http) —
  `/v1/evaluate`, `/v1/evaluate/batch` and `GET /v1/permissions` in detail,
  including delegated queries.

### Policy document shape

|        Field         |   Type   |                  Description                   |
|----------------------|----------|------------------------------------------------|
| `policyId`           | string   | Unique identifier for this policy              |
| `version`            | integer  | `1` on create; `N+1` on every append           |
| `resourceType`       | string   | The resource type this policy applies to       |
| `actions`            | string[] | Action strings or `["*"]` to match all         |
| `combiningAlgorithm` | string   | `DENY_OVERRIDES` (single applicable DENY wins) |
| `defaultEffect`      | string   | `PERMIT` or `DENY` when no rule matches        |
| `rules`              | Rule[]   | Ordered list of rules                          |

Each `Rule` has `id` (string), `effect` (`PERMIT`/`DENY`), and a `condition`.

**Condition types.** Leaf: `{"type":"comparison","op":"<OP>","left":<operand>,"right":<operand>}`.
Composites: `{"type":"and"|"or","conditions":[…]}`.

**Operators.** `EQ`, `NEQ`, `IN`, `NOT_IN` (equality / membership);
`GT`, `GTE`, `LT`, `LTE` (ordering — operands must be numeric).

**Operands.** Attribute reference: `{"ref":"subject.id"}` (paths: `subject.id`,
`subject.attr.*`, `resource.type`, `resource.id`, `resource.attr.*`, `context.*`).
Literal: `{"value":<json-value>}`.

**Authoring validation.** An ordering operator (`GT`/`GTE`/`LT`/`LTE`) with a
non-numeric literal is rejected at create/append (`400 INVALID_POLICY`). A reference
operand that resolves to a non-numeric value at evaluation time yields a deny — never
a 500 error.

---

## Project structure

```
service-policy/
├── pom.xml                          # Maven build descriptor
├── README.md                        # You are here
├── CONTRIBUTING.md                  # How to contribute
├── CODE_OF_CONDUCT.md               # Community standards
├── LICENSE                          # Apache 2.0
├── SECURITY.md                      # How to report vulnerabilities
├── DOCKER.md                        # Docker & Compose reference
├── release-please-config.json       # Automated release configuration
├── .release-please-manifest.json    # Current version manifest
├── .env.example                     # Environment variable template
├── docker-compose.yml               # JVM stack (Mongo + Mongo Express)
├── docker-compose.native.yml        # Native image stack
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                   # GitHub Actions CI
│   │   └── release-please.yml       # Automated releases
│   ├── ISSUE_TEMPLATE/              # Bug / feature / question forms
│   ├── PULL_REQUEST_TEMPLATE.md     # PR checklist
│   ├── CODEOWNERS                   # Default reviewers
│   └── dependabot.yml               # Automated dependency updates
├── docs/
│   ├── architecture.md              # Architecture deep-dive
│   ├── ERRORS.md                    # RFC 9457 error catalog (machine-readable codes)
│   ├── http/
│   │   ├── lifecycle-walkthrough.http   # create → version → activate → evaluate → deactivate + error contracts
│   │   └── evaluation-endpoints.http    # /v1/evaluate, /batch, /v1/permissions in detail
│   └── adr/                         # Architecture Decision Records (ADR-001 … ADR-023+)
└── src/
    ├── main/
    │   ├── docker/                  # Dockerfile.jvm / .legacy-jar / .native / .native-micro
    │   ├── java/io/github/ricardoqmd/servicepolicy/
    │   │   ├── config/              # Typed config (ServicePolicyConfig, AuthzConfigValidator)
    │   │   ├── domain/              # Pure domain — no framework dependencies (ArchUnit-guarded)
    │   │   │   ├── exception/       # Domain exceptions
    │   │   │   ├── model/           # AuthorizationRequest, Resource, Subject
    │   │   │   └── policy/          # Policy, Rule, Condition AST, ConditionEvaluator, PolicyEngine
    │   │   ├── evaluation/          # Port: PolicyEvaluator, PersistentPolicyEvaluator, DTOs
    │   │   ├── health/              # Liveness / readiness checks
    │   │   ├── persistence/         # MongoDB: mappers, repositories, PolicyLifecycleStore
    │   │   ├── problem/             # RFC 9457 ProblemException hierarchy
    │   │   └── rest/                # HTTP endpoints (EvaluateResource, PolicyResource, …)
    │   └── resources/
    │       └── application.yml      # Default configuration
    └── test/
        └── java/io/github/ricardoqmd/servicepolicy/
            ├── architecture/        # ArchUnit hexagonal layer rules (5 rules)
            ├── domain/policy/       # Pure domain unit tests
            ├── persistence/         # Mapper and repository tests
            └── *.java               # @QuarkusTest integration tests (evaluation, authoring, …)
```

---

## Roadmap

|  Phase  |                                                                                                                                                                                             Description                                                                                                                                                                                              | Status  |
|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|
| **1**   | **Skeleton.** Quarkus project, configuration, healthchecks, metrics, smoke tests, CI, Docker.                                                                                                                                                                                                                                                                                                        | ✅ Done  |
| **1.5** | **PEP contract surface + deterministic stub.** REST endpoints live (`/v1/evaluate`, `/v1/evaluate/batch`, `GET /v1/permissions`), OpenAPI contract frozen; no persistence; no JWT verification.                                                                                                                                                                                                      | ✅ Done  |
| **2**   | **Domain & persistence.** Policy model, condition AST, MongoDB integration; real `PolicyEvaluator` replaces the stub; deny-overrides combining algorithm.                                                                                                                                                                                                                                            | ✅ Done  |
| **3**   | **OIDC/JWKS validation.** Bearer-only resource server; mode-based authz markers (admin + delegation); hybrid subject-provenance rule; mandatory startup validation; stub removed.                                                                                                                                                                                                                    | ✅ Done  |
| **3.5** | **Policy authoring & lifecycle.** Full admin API in the PDP: create, append-only versioning, explicit activation, soft deactivation. Head-pointer persistence model (`policy_heads` + `policy_versions`). Error contract (RFC 9457 problem+json). Optimistic concurrency (If-Match/ETag). ArchUnit hexagonal layer guard. Coverage tooling (JaCoCo + SonarCloud). Operand-type validation (ADR-023). | ✅ Done  |
| **4**   | **PAP front-end.** Dedicated control-plane application (UI + backend) that consumes the policy authoring API already in this service. The policy CRUD / versioning / activation API itself is done (Phase 3.5); this phase is the separate PAP component.                                                                                                                                            | Planned |
| **5**   | **Production hardening.** TLS termination, secrets management, structured evaluation audit log.                                                                                                                                                                                                                                                                                                      | Planned |
| **6**   | **Kubernetes.** Helm charts, ConfigMaps, NetworkPolicies, HPA.                                                                                                                                                                                                                                                                                                                                       | Planned |

---

## Comparison with alternatives

|     Feature      |      Service Policy      |   Keycloak Authz    |     OPA      |   Casbin   |
|------------------|--------------------------|---------------------|--------------|------------|
| Protocol         | REST/JSON                | UMA 2.0 / REST      | REST/gRPC    | Library    |
| Policy storage   | MongoDB                  | Keycloak DB         | Bundle / API | File / DB  |
| Policy language  | JSON AST (v1)            | GUI / JSON          | Rego         | PERM model |
| ABAC conditions  | Yes                      | Limited             | Yes          | Yes        |
| Audit log        | Lifecycle (eval planned) | Limited             | Yes (OPA)    | No         |
| Separate service | Yes                      | Bundled in Keycloak | Yes          | No         |
| Java 21 native   | Yes                      | No                  | No           | No         |
| Kubernetes-ready | Yes                      | Yes                 | Yes          | No         |
| Learning curve   | Low                      | Medium              | High (Rego)  | Medium     |

> This is not a port of any of these. Service Policy is an opinionated alternative
> for teams that want full control over their authorization stack without operating
> a separate policy server next to Keycloak.

---

## Tooling

|                                                            Tool                                                             |                   Purpose                    |
|-----------------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| [Spotless](https://github.com/diffplug/spotless) + [Palantir Java Format](https://github.com/palantir/palantir-java-format) | Deterministic code formatting                |
| [JaCoCo](https://www.jacoco.org/) + [quarkus-jacoco](https://quarkus.io/guides/tests-with-coverage)                         | Code coverage measurement and enforcement    |
| [SonarCloud](https://sonarcloud.io/)                                                                                        | Continuous code quality and coverage gate    |
| [ArchUnit](https://www.archunit.org/)                                                                                       | Hexagonal layer invariants enforced at build |
| [GitHub Actions](https://docs.github.com/en/actions)                                                                        | CI pipeline (build, test, gitleaks)          |
| [Conventional Commits 1.0.0](https://conventionalcommits.org)                                                               | Structured commit history                    |
| [Semantic Versioning 2.0.0](https://semver.org)                                                                             | Release versioning                           |
| [Release Please](https://github.com/googleapis/release-please)                                                              | Automated CHANGELOG and releases             |

Run `./mvnw spotless:apply` before committing to auto-format all Java and
Markdown sources.

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for
development setup, code style, commit message conventions, and the PR process.

For security vulnerabilities, see [SECURITY.md](SECURITY.md) — do not open a
public issue.

---

## License

Apache License 2.0. See [LICENSE](LICENSE) for the full text.

---

## Author

**Ricardo Quintero Mármol Durán** ([@ricardoqmd](https://github.com/ricardoqmd))

---

## Acknowledgments

Service Policy draws inspiration from:

- [XACML 3.0](https://docs.oasis-open.org/xacml/3.0/xacml-3.0-core-spec-os-en.html) — the formal ABAC model.
- [Cedar](https://www.cedarpolicy.com/) — Amazon's policy language.
- [Open Policy Agent](https://www.openpolicyagent.org/) — the cloud-native PDP.
- [Casbin](https://casbin.org/) — pragmatic open source authorization.

