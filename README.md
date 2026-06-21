[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ricardoqmd_service-policy&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ricardoqmd_service-policy)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=ricardoqmd_service-policy&metric=coverage)](https://sonarcloud.io/summary/new_code?id=ricardoqmd_service-policy)
[![CI](https://github.com/ricardoqmd/service-policy/actions/workflows/ci.yml/badge.svg)](https://github.com/ricardoqmd/service-policy/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.33%20LTS-blueviolet.svg)](https://quarkus.io/)
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

- **Policy Decision Point (PDP)** — this service. Receives an authorization
  query and returns an allow/deny decision (boolean `allowed`).
- **Policy Enforcement Point (PEP)** — your application, API gateway, or
  frontend. Calls this service and acts on the result.
- **Policy Administration Point (PAP)** — your admin tooling. Manages policies
  through the admin API (Phase 6).

For the full architecture rationale, trade-offs, and query model see
[docs/architecture.md](docs/architecture.md).

---

## PEP contract (v1)

Three endpoints form the stable contract that Policy Enforcement Points integrate against.
The full machine-readable spec lives in [docs/openapi.yaml](docs/openapi.yaml).

| Method |         Path         |                      Description                       |
|--------|----------------------|--------------------------------------------------------|
| POST   | `/v1/evaluate`       | Single authorization request → `Decision` (allow/deny) |
| POST   | `/v1/evaluate/batch` | Batch of requests → list of `Decision`                 |
| GET    | `/v1/permissions`    | Cacheable flat permission list for a subject + `?app=` |

Subject is always resolved from `Authorization: Bearer <jwt>` — never from the request body.
`GET /v1/permissions` responses are safe to cache per `subject + app + policyVersion`.

Relevant ADRs:
[ADR-004 — contract surface & stub](docs/adr/004-pep-contract-surface-and-stub.md) ·
[ADR-005 — attribute id/code keying](docs/adr/005-attribute-id-keying.md) ·
[ADR-006 — tenancy model](docs/adr/006-tenancy-model.md)

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
│   ├── openapi.yaml                 # Frozen PEP contract (generated at build)
│   └── adr/                         # Architecture Decision Records
│       ├── 001-dedicated-abac-pdp.md
│       ├── 002-quarkus-java21.md
│       ├── 003-authentication-oidc-jwt-jwks.md
│       ├── 004-pep-contract-surface-and-stub.md
│       ├── 005-attribute-id-keying.md
│       └── 006-tenancy-model.md
└── src/
    ├── main/
    │   ├── docker/                  # Dockerfile.jvm / .legacy-jar / .native / .native-micro
    │   ├── java/io/github/ricardoqmd/servicepolicy/
    │   │   ├── config/              # Typed config (ServicePolicyConfig, StubConfig)
    │   │   ├── evaluation/          # Port + stub: PolicyEvaluator, StubPolicyEvaluator, DTOs
    │   │   ├── health/              # Liveness / readiness checks
    │   │   └── rest/                # HTTP endpoints (EvaluateResource, PermissionsResource, …)
    │   └── resources/
    │       └── application.yml      # Default configuration
    └── test/
        └── java/io/github/ricardoqmd/servicepolicy/
            ├── ApplicationSmokeTest.java
            └── EvaluationResourceTest.java
```

---

## Roadmap

|  Phase  |                                                                                           Description                                                                                           | Status  |
|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|
| **1**   | **Skeleton.** Quarkus project, configuration, healthchecks, metrics, smoke tests, CI, Docker.                                                                                                   | ✅ Done  |
| **1.5** | **PEP contract surface + deterministic stub.** REST endpoints live (`/v1/evaluate`, `/v1/evaluate/batch`, `GET /v1/permissions`), OpenAPI contract frozen; no persistence; no JWT verification. | ✅ Done  |
| **2**   | **Domain & persistence.** Policy model, condition AST (Phase 2 future ADR), MongoDB integration; real `PolicyEvaluator` replaces the stub.                                                      | Planned |
| **3**   | **JWT validation.** Keycloak JWKS; real subject + realm/tenant extraction ([ADR-006](docs/adr/006-tenancy-model.md)); stub removed.                                                             | Planned |
| **4**   | **Admin API / PAP.** Policy CRUD, versioning, audit log.                                                                                                                                        | Planned |
| **5**   | **Production hardening.** TLS, secrets management, structured audit.                                                                                                                            | Planned |
| **6**   | **Kubernetes.** Helm charts, ConfigMaps, NetworkPolicies, HPA.                                                                                                                                  | Planned |

---

## Comparison with alternatives

|     Feature      |  Service Policy   |   Keycloak Authz    |     OPA      |   Casbin   |
|------------------|-------------------|---------------------|--------------|------------|
| Protocol         | REST/JSON         | UMA 2.0 / REST      | REST/gRPC    | Library    |
| Policy storage   | MongoDB (Phase 2) | Keycloak DB         | Bundle / API | File / DB  |
| Policy language  | JSON AST (v1)     | GUI / JSON          | Rego         | PERM model |
| ABAC conditions  | Yes (roadmap)     | Limited             | Yes          | Yes        |
| Audit log        | Yes (roadmap)     | Limited             | Yes (OPA)    | No         |
| Separate service | Yes               | Bundled in Keycloak | Yes          | No         |
| Java 21 native   | Yes               | No                  | No           | No         |
| Kubernetes-ready | Yes               | Yes                 | Yes          | No         |
| Learning curve   | Low               | Medium              | High (Rego)  | Medium     |

> This is not a port of any of these. Service Policy is an opinionated alternative
> for teams that want full control over their authorization stack without operating
> a separate policy server next to Keycloak.

---

## Tooling

|                                                            Tool                                                             |               Purpose               |
|-----------------------------------------------------------------------------------------------------------------------------|-------------------------------------|
| [Spotless](https://github.com/diffplug/spotless) + [Palantir Java Format](https://github.com/palantir/palantir-java-format) | Deterministic code formatting       |
| [JaCoCo](https://www.jacoco.org/)                                                                                           | Code coverage enforcement           |
| [GitHub Actions](https://docs.github.com/en/actions)                                                                        | CI pipeline (build, test, gitleaks) |
| [Conventional Commits 1.0.0](https://conventionalcommits.org)                                                               | Structured commit history           |
| [Semantic Versioning 2.0.0](https://semver.org)                                                                             | Release versioning                  |
| [Release Please](https://github.com/googleapis/release-please)                                                              | Automated CHANGELOG and releases    |

Run `./mvnw spotless:apply` before committing to auto-format all Java and
Markdown sources.

### Considered but not included (yet)

- **Codecov / SonarCloud** — planned for v1.0 once there is meaningful code
  coverage to report. Adding them at the skeleton stage creates noise without
  signal.

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

