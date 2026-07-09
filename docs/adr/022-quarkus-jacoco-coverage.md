# 22. Adopt quarkus-jacoco for coverage instrumentation (revisits ADR-009)

- **Status:** Accepted
- **Date:** 2026-07-08
- **Deciders:** Ricardo
- **Revisits:** ADR-009 (test coverage tooling and CDI scope convention)

## Context

ADR-009 chose the standard `jacoco-maven-plugin` and, coupled to it, the
convention of annotating stateless beans `@Singleton` rather than
`@ApplicationScoped`. The reason recorded at the time was that with
`@ApplicationScoped` the tests were not counted by the coverage tooling, and
`@Singleton` was adopted partly to make SonarCloud/GitHub Actions see the
coverage.

The evaluator-cutover work (ADR-021) exposed the real, underlying problem. The
`PolicyHeadRepository` and `PolicyVersionRepository` Panache repositories reported
**0% coverage** in SonarCloud even though every one of their methods is exercised
by the test suite. The cause is structural: Quarkus rewrites CDI beans and Panache
repositories during build-time augmentation, and the standard JaCoCo agent
instruments the original bytecode, not the augmented classes that actually run — so
executions are never attributed. No additional test can fix this while the standard
agent is used; the layer is simply invisible to it. This blocked the ADR-021 pull
request on the SonarCloud "coverage on new code ≥ 80%" gate (it sat at 77.8%,
dragged down entirely by the two 0% repositories).

## Decision

Adopt the **`quarkus-jacoco`** extension (test-scoped dependency) as the coverage
**agent**, and keep `jacoco-maven-plugin` solely as the **report and gate**
consumer of the execution data it produces.

- `quarkus-jacoco` injects its own agent, integrated with the Quarkus class loader,
  so it instruments the augmented CDI beans and Panache repositories. It writes
  execution data to `target/jacoco-quarkus.exec`.
- The standard `jacoco-maven-plugin` no longer runs `prepare-agent`. Its `report`
  and `check` executions read `target/jacoco-quarkus.exec` (via `<dataFile>`) and
  produce `target/site/jacoco/jacoco.xml`, which is the path SonarCloud already
  consumes (`sonar.coverage.jacoco.xmlReportPaths`). The empty `<argLine/>` default
  is kept so the Surefire `@{argLine}` reference resolves.

This was validated by experiment before adoption (see Reasons).

### The `@Singleton` convention is retained, for a different reason

ADR-009 coupled `@Singleton` to coverage. With `quarkus-jacoco` that coupling no
longer holds: the extension would now instrument `@ApplicationScoped` beans
correctly, so the *coverage* reason for `@Singleton` no longer applies.

The `@Singleton` convention is nonetheless **kept**, on its own independent merit:
stateless beans do not need the client proxy that `@ApplicationScoped` imposes, so
`@Singleton` is the more appropriate scope for them regardless of coverage tooling.
ADR-009's coverage-driven justification for the convention is superseded by this
ADR; the convention itself stands on the proxy argument.

## Reasons

- **It fixes the real cause, not a symptom.** The problem was agent/augmentation
  mismatch, and `quarkus-jacoco` is the Quarkus-native mechanism built for exactly
  this. Both repositories moved from 0% to 100% instruction coverage.
- **Evidence-driven, chosen over the alternatives by experiment.** On a throwaway
  branch: (1) forcing the standard agent with `inclNoLocationClasses=true` crashed
  the JVM on Java 21 while instrumenting JDK internals — rejected; (2) `quarkus-jacoco`
  worked cleanly — repositories 0%→100%, total instruction coverage ~80.3%→82.9%,
  **no regression** on any previously-covered class (same test count), build green,
  and SonarCloud successfully imported the resulting `jacoco.xml`.
- **Honest measurement over exclusion.** The considered alternative — excluding the
  repositories from coverage — would have passed the gate by declaring the layer
  unmeasured. For a public portfolio repository this reads as lowering the bar.
  Real coverage of the layer is both more truthful and more credible.
- **Minimal, reversible surface.** One test-scoped dependency, the removal of the
  standard `prepare-agent`, and a `<dataFile>` redirect. No production code changes.

## Alternatives considered

- **Standard `jacoco-maven-plugin` with `inclNoLocationClasses=true`.** Intended to
  make JaCoCo count classes without source-line info (the augmented classes).
  Rejected: crashes the JVM on Java 21 when it reaches JDK-internal classes during
  instrumentation.
- **`sonar.coverage.exclusions` for the repository layer.** Exclude Panache
  repositories from coverage on the grounds that they are one-line declarative
  queries already exercised by integration tests. Rejected as the primary solution:
  it hides a measurable layer rather than measuring it, and on a public repo it
  undermines the credibility of the coverage number. Retained only as the fallback
  had `quarkus-jacoco` failed.
- **Lower the SonarCloud new-code coverage threshold below 80%.** Rejected: degrades
  the standard for all new code to accommodate a single tooling limitation.
- **Rewrite repositories to avoid Panache's declarative API** so the standard agent
  can instrument them. Rejected: discards Panache's value and adds bug surface to
  satisfy a measurement tool.

### Better option not omitted (and its impact)

- **Align the local JaCoCo `check` gate with the SonarCloud threshold.** The local
  `jacoco-check` currently enforces only `minimum 0.00`, so `./mvnw verify` never
  fails on coverage and the first signal of a gap is SonarCloud on the PR. Raising
  the local minimum (e.g. to match the 80% new-code intent) would surface coverage
  regressions before pushing. Impact: a stricter local build that can block commits
  during exploratory work, and BUNDLE-level vs new-code semantics differ, so the
  numbers are not identical. Deferred, not omitted: adjust the local gate as a
  separate change once the team wants local/CI parity; it is orthogonal to the
  agent fix.

## Consequences

- Panache repositories and other Quarkus-augmented beans are now measured; coverage
  numbers reflect the tests that actually run.
- `quarkus-jacoco` is a test-scoped dependency; the coverage agent is now tied to
  the Quarkus test infrastructure rather than a Maven plugin goal. The
  `jacoco-maven-plugin` remains only to render the report and enforce the gate.
- ADR-009's coverage-based rationale for `@Singleton` is superseded; the convention
  persists on the CDI-proxy rationale stated above. New stateless beans should still
  be `@Singleton`.
- The local coverage gate remains permissive (`minimum 0.00`); local/CI parity is a
  deferred follow-up.
- This pattern (standard JaCoCo cannot measure Quarkus-augmented classes; use
  `quarkus-jacoco`) is a candidate for promotion to `team-standards` when a second
  public Quarkus repository adopts it — not promoted preventively.

## Criteria to revisit

- A future Quarkus/JaCoCo release makes the standard agent instrument augmented
  classes correctly → the extra extension could be dropped.
- A second Quarkus repo needs the same fix → promote the decision to
  `team-standards` rather than re-deciding per repo.
- Local/CI coverage-gate parity becomes desirable → raise the local `jacoco-check`
  minimum as its own change.

