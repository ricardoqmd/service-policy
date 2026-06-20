# ADR-002: Quarkus 3.x LTS + Java 21

| Field  |             Value             |
|--------|-------------------------------|
| Status | Accepted                      |
| Date   | 2026-05-09                    |
| Author | Ricardo Quintero Mármol Durán |

---

## Context

Service Policy is a new greenfield service. The team needs to select a Java web framework and Java
version. The primary requirements are:

- **Low operational overhead.** The service must be deployable as a container with fast startup and
  low memory footprint, and must expose health, metrics, and OpenAPI out of the box.
- **12-factor compliance.** All configuration via environment variables; no file-system state.
- **Native image optionality.** The ability to compile to a GraalVM native executable for
  environments where sub-second startup or very low memory is required.
- **Long-term maintainability.** A stable, LTS-aligned release to avoid chasing quarterly upgrades.
- **MicroProfile APIs.** Health, Metrics, OpenAPI, and Config as standard interfaces, not
  framework-specific APIs, to keep the code portable.

---

## Decision

Use **Quarkus 3.33.1 LTS** as the web framework and **Java 21** as the language version.

### Quarkus

Quarkus was selected because it satisfies all requirements without requiring custom wiring:

- MicroProfile Health, Metrics (Prometheus), and OpenAPI extensions are first-class and
  configuration-driven.
- `quarkus-config-yaml` and `@ConfigMapping` support typed configuration that maps naturally to
  12-factor env var overrides.
- The GraalVM native image pipeline is integrated and tested by the Quarkus team across all
  supported extensions.
- The **LTS designation** of version 3.33.1 provides a 24-month maintenance window, ensuring
  security patches without forcing feature upgrades.
- REST endpoints use JAX-RS annotations (`quarkus-rest` + `quarkus-rest-jackson`), which are
  familiar and do not require learning a Quarkus-specific DSL.

### Java 21

Java 21 was selected because it is the most recent **LTS release** at project inception and
provides:

- **Records** — clean, immutable DTOs with no boilerplate (`record Decision(boolean allowed,
  String reason, ...)`)
- **Pattern matching** for `instanceof` and `switch` — cleaner condition evaluation code in future
  phases.
- **Virtual threads** (`java.lang.Thread` via Project Loom) — available if blocking I/O patterns
  emerge in Phase 2+.
- Mainstream adoption by all major deployment targets (GraalVM, Corretto, OpenJDK).

---

## Alternatives considered

### Spring Boot 3.x

Spring Boot is mature and widely known. Rejected because:
- More boilerplate to achieve the same MicroProfile-equivalent features.
- Native image support via Spring AOT is functional but less mature than Quarkus at the time of
selection.
- Container images are generally larger than Quarkus equivalents for the same functionality.

### Micronaut 4.x

Micronaut is technically comparable to Quarkus in startup time and native image support. Rejected
because:
- Smaller community and ecosystem than Quarkus at the time of selection.
- Less mature MicroProfile alignment (Micronaut follows its own API conventions).

### Vert.x (standalone)

Vert.x offers excellent performance but requires more explicit wiring of components. Rejected
because the reactive/callback programming model increases cognitive load for a service whose
primary bottleneck is not I/O throughput.

### Java 17 (previous LTS)

Java 17 is still supported. Rejected because Java 21 is now the current LTS, and starting on 17
would require an upgrade before the service reaches production maturity.

---

## Consequences

**Positive:**
- Zero boilerplate for health checks, metrics, and OpenAPI — they are available via configuration,
not code.
- The LTS designation gives a stable target for CI and dependency upgrades.
- Native image build is available from day one for environments that need it.
- Java records simplify DTO definitions and reduce accidental mutability.

**Negative:**
- Quarkus' CDI implementation (ArC) has some differences from the full CDI specification; edge
cases in bean resolution require attention during Phase 2+ when the object graph grows.
- GraalVM native image compilation is slow (minutes) and requires careful reflection configuration
for any dynamically loaded classes.
