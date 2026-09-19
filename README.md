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

### Control-plane authorization (ADR-033)

Every control-plane endpoint — policies, simulation, action catalogue and configuration,
reads included — is authorized **per application, by policy**. There is no administrative
role or scope, and no setting that restores one.

- **The decision.** The engine evaluates its own control-plane policy set, over the
  resource type `policy` and the actions `policy:read`, `policy:write`, `policy:activate`
  and `policy:deactivate`. The application being decided about is the one in the route,
  as `resource.attr.app`. Installation seeds one baseline rule:
  `permit when resource.attr.app IN subject.attr.apps`.
- **Whose attributes.** The caller's own, derived from its **validated token** through the
  claim mapping stored for the **reserved control-plane application** — never from a
  request body, and never from the configuration of the application being decided about.
- **Where the rules live.** In one reserved application (default
  `service-policy-control-plane`). Finer grants are policies authored there through the
  ordinary API; under deny-overrides every policy selected for a verb must permit it, so a
  grant that widens the baseline is written into the policy that governs that verb.
- **Denials.** A refused call is `403 FORBIDDEN` with the same body whether or not the
  application exists. The merged catalogue `GET /v1/policies` is scoped to the
  applications you may read: `200` with an empty page when there are none.
- **On whose behalf.** A write may carry the body field `subject`. It authorizes nothing:
  the audit records `createdBy` (the calling credential), `subject` and
  `subjectProvenance` — `VERIFIED` when the identity is the token's own, `DECLARED` when
  the caller asserted it.

|                                               Property (env var)                                                |            Default             |                                                                                                           Meaning                                                                                                           |
|-----------------------------------------------------------------------------------------------------------------|--------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `service-policy.control-plane.subject-attributes.apps` (`SERVICE_POLICY_CONTROL_PLANE_SUBJECT_ATTRIBUTES_APPS`) | —                              | Claim path carrying the caller's applications. **Required until installation**: a service that is not installed and lacks it does not start. Ignored afterwards (a divergence is logged once).                              |
| `service-policy.control-plane.bootstrap.value` (`SERVICE_POLICY_CONTROL_PLANE_BOOTSTRAP_VALUE`)                 | —                              | Claim value of the one caller accepted before installation. **Required until installation**: without it no caller could make the write that closes installation, so the service does not start.                             |
| `service-policy.control-plane.bootstrap.claim` (`SERVICE_POLICY_CONTROL_PLANE_BOOTSTRAP_CLAIM`)                 | `sub`                          | Claim path that value is read from.                                                                                                                                                                                         |
| `service-policy.control-plane.reserved-app` (`SERVICE_POLICY_CONTROL_PLANE_RESERVED_APP`)                       | `service-policy-control-plane` | Where the control-plane rules and mapping are kept. **Fixed at installation**: the marker records it; a deployment configured with a different one does not start, and one already running denies every control-plane call. |

**Installation.** At startup, a store with no installation marker is seeded with the reserved
application's configuration (`apps` → the configured claim path), its catalogue, and the active
baseline policy — **only into an empty reserved application**. If that application already holds
any document installation did not write, the service refuses to start, writes nothing, and names
what is in the way — the configuration, each catalogue entry, each policy once however many of its
documents are foreign — up to ten names and a count of the rest (see *Upgrading a store that
already uses the reserved name* below). Seeding finishes what it started: a start interrupted
half-way leaves part of installation's own documents, and the next start completes them — a
missing version 1 is written, an inactive baseline is activated — then checks that the
configuration, the catalogue entry and the active baseline are all there, and refuses to start if
they are not. Over a complete installation a restart changes nothing. Until the first successful
control-plane write, only the bootstrap subject is accepted; that
write records a one-way marker and installation mode ends for good. The bootstrap value grants
nothing afterwards, deleting policies does not reopen it, and nothing in the service deletes
the marker. From then on the mapping is changed with `PUT` on the reserved application's
configuration, which refuses a mapping that would leave its own caller without the reserved
application; that configuration cannot be created or deleted through the API.

The marker also records **which application is reserved**, so that identifier belongs to the
installation and not to the running configuration: a deployment configured with a different one
**does not start**, and the refusal names the recorded value; an instance that was already
running under a different one when installation closed denies every control-plane call, and logs
why once. A blank identifier is refused at startup. A marker in the shape an earlier build wrote
is refused too — the store predates the build and has to be reinstalled; the identifier is
neither guessed nor backfilled. Startup likewise refuses, rather than warning, when an installed
store's reserved application holds no usable `apps` mapping — none, or a value that is not a
non-empty claim path: every control-plane decision reads that mapping, so it would deny everyone,
with nobody left to repair it through the API. The refusal says how to repair it in the store: a
configuration document for the reserved application with `subjectAttributes.apps` set to a
non-empty claim path, `schemaVersion` `1` and `revision` as a 64-bit integer (for example
`NumberLong(1)`), which the API can then read and update.

The installation identity, `service-policy:installation`, is installation's alone: a caller whose
subject resolves to it — through `sub`, or `preferred_username` when there is no `sub` — is refused
every control-plane call, so no caller can write a document carrying installation's audit.

#### Upgrading to 0.6.0 — breaking

The global administrative marker (`service-policy.authz.admin.*`, default role
`authz-admin`) is removed, and every control-plane caller is now authorized per application.
A deployment that still sets `service-policy.authz.admin.*` in a configuration file fails to
start with an unknown-property error (`SRCFG00050`); remove it. The same setting as an
environment variable (`SERVICE_POLICY_AUTHZ_ADMIN_*`) is ignored and grants nothing; remove it
too. To migrate:

1. Make the tokens of each administrative caller carry the applications it administers in a
   claim, and set `SERVICE_POLICY_CONTROL_PLANE_SUBJECT_ATTRIBUTES_APPS` to that claim's
   path. Include the reserved application for the callers that administer the control plane
   itself.
2. Set `SERVICE_POLICY_CONTROL_PLANE_BOOTSTRAP_VALUE` to the `sub` of the credential that
   performs installation.
3. **Stop every instance of the previous version**, then start this one — not as a rolling
   update, and not blue/green with the previous version still live. The previous version honours
   the global marker and ignores the gate, so an instance of it left running against the same
   store can replace the policy set this version seeds, before or after installation closes, and
   the access this release removes would survive the upgrade. Startup seeds the control-plane
   policy set. **From here until step 4
   every console is refused**: installation mode accepts the bootstrap subject and nobody else.
   In the meantime the merged catalogue `GET /v1/policies` answers `200` with an empty page
   rather than `403`, because an empty scope is answered that way; it is not data loss, and the
   policies reappear once installation is closed.
4. Perform one control-plane write with the bootstrap credential — this closes installation.
   It is an ordinary control-plane write, and not every attempt closes: a `PUT` of the reserved
   application's configuration made by a bootstrap credential that does **not** itself carry the
   reserved application is refused by the self-lockout guard (`400 INVALID_APP_CONFIG`) and
   leaves installation mode open. Prefer closing it with a write made by a credential that
   **already carries the reserved application** in its claim — for example a `PUT` of that
   configuration with the mapping you intend to keep. The mapping is then proven against a real
   token before the close makes it irreversible.
5. Deploy the updated console.

#### Upgrading a store that already uses the reserved name

Installation seeds only into an empty reserved application. If the previous version was used to
write anything under that name (`service-policy-control-plane` unless you configured another),
this version refuses to start and names what is in the way — the configuration, each catalogue
entry by resource type, each policy by id — and nothing is adopted or deleted. The order that
works:

1. **With the previous version still running**, list what the reserved application holds: its
   policies, its action catalogue and its configuration.
2. **Decide.** If it holds **any policy**, the only way out is to set
   `SERVICE_POLICY_CONTROL_PLANE_RESERVED_APP` to an application that does not exist yet:
   policies cannot be removed through the API (versions are immutable), and deactivating one is
   not enough. If it holds only a configuration and catalogue entries, you may instead delete them
   through the previous version's API.
3. **Then upgrade**, stopping every instance of the previous version first (step 3 above). If
   startup still refuses, the message lists what remains.

### Delegation marker (ADR-013)

The delegation marker gates delegated data-plane queries. It has a configurable **mode**
(`role` or `scope`) so it maps cleanly to any IdP:

|   Marker   | Default mode | Default value |                 Controls                 |
|------------|--------------|---------------|------------------------------------------|
| delegation | `role`       | `pdp-client`  | Explicit `subject` ≠ caller (data plane) |

**mode=role** (Keycloak default): the check is `identity.hasRole(configuredRole)`.
The role claim location defaults to `realm_access/roles`; override for other IdPs:

```
QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH=roles          # Auth0 / Okta flat roles
```

**mode=scope** (Auth0 / Okta M2M tokens): the check splits the `scope` claim on whitespace
and checks membership:

```
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

All policy authoring and lifecycle endpoints are authorized per application (see
[Control-plane authorization](#control-plane-authorization-adr-033) above). Errors follow the
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
Authorization: Bearer <token-carrying-the-app-in-apps>
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
Authorization: Bearer <token-carrying-the-app-in-apps>
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

