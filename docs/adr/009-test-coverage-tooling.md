# 9. Test coverage tooling and CDI scope convention

Date: 2026-06-27

## Status

Accepted

## Context

Coverage is enforced by the `jacoco-maven-plugin` (agent + report + check) and the JaCoCo
XML report is fed to SonarCloud, whose quality gate enforces coverage on new code.

A recurring friction surfaced three times: a stateless CDI bean annotated
`@ApplicationScoped` reports its constructor as uncovered. For a normal-scoped bean, ArC
creates a client proxy via `Unsafe.allocateInstance()`, which bypasses the constructor, so
JaCoCo's probes never fire on the proxy. The constructor and field initializers then count
as new uncovered lines and fail the quality gate, even though the bean is fully exercised by
tests.

`quarkus-jacoco` was the coverage tool originally proposed in the broader architecture, so
the choice between it and the current setup is worth recording explicitly.

## Decision

1. Keep `jacoco-maven-plugin` as the coverage tool. Do not adopt `quarkus-jacoco`.
2. Convention: annotate **stateless beans with `@Singleton`**; reserve `@ApplicationScoped`
   for beans that genuinely require normal scope (request-spanning mutable state, lazy
   proxying, and similar).

## Reasons

- `quarkus-jacoco`'s automatic configuration only measures tests annotated with
  `@QuarkusTest`; coverage of plain JUnit tests requires falling back to the
  `jacoco-maven-plugin` (Quarkus "Measuring the coverage of your tests" guide). This
  codebase is predominantly plain unit tests (domain, mappers), so `quarkus-jacoco` alone
  would report them as roughly 0% and collapse the gate.
- The "run both tools and merge the `.exec` files" workaround is fragile: it commonly yields
  either a 0% report or a `@QuarkusTest`-only report, with issues still open upstream as of
  late 2025. Not worth the CI instability for a solo-maintained repository.
- `@Singleton` is the semantically correct scope for a stateless bean (lighter, no proxy);
  JaCoCo measuring its constructor correctly is a consequence, not the motive. Making it the
  default removes the friction at the source — new beans are born `@Singleton`.

## Alternatives considered

- **Adopt `quarkus-jacoco` alone:** rejected — drops coverage of non-`@QuarkusTest` tests,
  which are the majority here.
- **`quarkus-jacoco` + `jacoco-maven-plugin` with merged `.exec`:** rejected — fragile,
  recurring upstream defects, added CI surface for little gain.
- **Keep `@ApplicationScoped` and exclude constructors from coverage:** rejected — games the
  metric and hides real gaps.

## Consequences

- (+) Coverage is measured correctly across the mostly-unit-test suite; the gate is stable.
- (+) A documented scope convention; no per-PR coverage surprises.
- (−) A bean that genuinely needs `@ApplicationScoped` and is exercised only via
  `@QuarkusTest` may still show an uncovered constructor; handle case by case (a small unit
  test, or accept the minor gap).
- (−) Divergence from the originally proposed `quarkus-jacoco`; recorded here and propagated
  to the department backend standards.

## Revisit if

- The suite shifts to be predominantly `@QuarkusTest` (the `quarkus-jacoco` trade-off flips).
- Quarkus resolves mixed plain-JUnit / `@QuarkusTest` coverage cleanly.

Reference: Quarkus, "Measuring the coverage of your tests"
(https://quarkus.io/guides/tests-with-coverage).
