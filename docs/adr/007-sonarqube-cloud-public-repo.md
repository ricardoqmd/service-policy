# ADR-007 — SonarQube Cloud for the public repository

**Date:** 2026-06-20 · **Status:** Accepted · **Deciders:** Ricardo

**Context.** The department standard for code quality is self-hosted SonarQube,
analyzed via Jenkins for internal projects. service-policy is a public GitHub repo
with CI on GitHub Actions and a portfolio piece. Pointing the internal instance at a
public repo is awkward: the analysis would not be publicly visible and it couples
public CI to private infrastructure. The auth monorepo resolved this exact tension.

**Decision.** Use SonarQube Cloud (CI-based analysis, not Automatic Analysis). The
Maven build runs `sonar:sonar` with `-Dsonar.qualitygate.wait=true` inside the
build-and-test job; coverage comes from the JaCoCo XML report. The gate follows Clean
as You Code (threshold on new code). The self-hosted instance remains the standard for
internal projects; an internal deployment of service-policy would use it via Jenkins.

**Rationale.** Visible quality gate + coverage badge (portfolio evidence); catch issues
during development, not at release; CI-based analysis is required for Java/Maven so
Sonar sees compiled classes and JaCoCo coverage. Mirrors the auth decision for
consistency.

**Alternatives.** (A) Self-hosted (literal standard) — not visible on a public repo,
couples public CI to private infra. (B) Automatic Analysis — ignores JaCoCo coverage
for Java and conflicts with CI-based analysis.

**Consequences.** (+) Quality measured and visible from day one; gate blocks merges via
the existing required check. (−) A second quality setup in the ecosystem (internal
self-hosted / public Cloud), intentional and precedented by auth.

**Revisit if.** The repo stops being public, or the organization requires analysis only
on internal infrastructure.
