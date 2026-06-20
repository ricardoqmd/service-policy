# Contributing to Service Policy

Thank you for considering a contribution to Service Policy! This document
explains everything you need to know to go from idea to merged pull request.

- [Code of Conduct](#code-of-conduct)
- [Quick links](#quick-links)
- [Development setup](#development-setup)
- [Code style](#code-style)
- [Commit messages](#commit-messages-conventional-commits)
- [Branching strategy](#branching-strategy)
- [Pull request process](#pull-request-process)
- [Versioning](#versioning)
- [Release process](#release-process)
- [Questions?](#questions)

---

## Code of Conduct

This project follows the [Contributor Covenant 2.1](CODE_OF_CONDUCT.md).
By participating you agree to abide by its terms.

---

## Quick links

- [Report a bug](https://github.com/ricardoqmd/service-policy/issues/new?template=bug_report.yml)
- [Request a feature](https://github.com/ricardoqmd/service-policy/issues/new?template=feature_request.yml)
- [Ask a question](https://github.com/ricardoqmd/service-policy/issues/new?template=question.yml)
- [Report a vulnerability](SECURITY.md)

---

## Development setup

### Requirements

|    Tool    |                   Version                   |
|------------|---------------------------------------------|
| Java (JDK) | 21+                                         |
| Maven      | 3.9+ (or use the included `./mvnw` wrapper) |
| Docker     | Optional — needed for the full stack        |

### Clone and verify

```bash
git clone https://github.com/ricardoqmd/service-policy.git
cd service-policy

# Compile, run tests, and check code style
./mvnw clean verify
```

### Run in dev mode

```bash
./mvnw quarkus:dev
```

Live reload is enabled. Changes to source files take effect on the next HTTP
request without restarting.

### Run the full stack (Docker Compose)

```bash
cp .env.example .env          # adjust ports or credentials if needed
docker compose up -d          # MongoDB + Mongo Express + service-policy (JVM)
```

See [DOCKER.md](DOCKER.md) for all Docker and Compose details.

---

## Code style

This project uses **[Spotless](https://github.com/diffplug/spotless)** with
**[Palantir Java Format](https://github.com/palantir/palantir-java-format)**
to enforce a deterministic, diff-friendly code style. There is nothing to
configure — just run the formatter before you commit:

```bash
./mvnw spotless:apply
```

Spotless also formats Markdown files. The CI pipeline runs `spotless:check` and
will fail if any file is not formatted.

### Useful Maven commands

```bash
# Format all sources
./mvnw spotless:apply

# Full build with tests and quality checks
./mvnw clean verify

# Skip quality checks during rapid iteration (use sparingly)
./mvnw clean verify -Pskip-quality
```

---

## Commit messages: Conventional Commits

This project follows **[Conventional Commits 1.0.0](https://conventionalcommits.org)**.
Every commit on `main` must match the format below. PRs are squash-merged, so
the PR title becomes the commit message — make it count.

### Format

```
<type>(<scope>): <short description>

[optional body]

[optional footer(s)]
```

- **type** — see the table below (required)
- **scope** — the area of the codebase affected, e.g. `policy`, `auth`, `ci`
  (optional but recommended)
- **short description** — imperative mood, no period, ≤72 chars (required)

### Types

|    Type    |                     When to use                     |
|------------|-----------------------------------------------------|
| `feat`     | A new feature visible to users or API consumers     |
| `fix`      | A bug fix                                           |
| `docs`     | Documentation only changes                          |
| `style`    | Formatting, whitespace — no logic change            |
| `refactor` | Code restructuring — no feature added, no bug fixed |
| `perf`     | A change that improves performance                  |
| `test`     | Adding or fixing tests                              |
| `build`    | Changes to the build system or dependencies         |
| `ci`       | Changes to CI configuration or scripts              |
| `chore`    | Maintenance — dependency bumps, tool config         |
| `revert`   | Reverts a previous commit                           |

### Examples

```
feat(policy): add condition evaluation for string attributes
```

```
fix(health): return 503 when MongoDB is unreachable
```

```
docs: add architecture decision record for query model
```

```
ci: add gitleaks secret scanning to CI pipeline
```

### Breaking changes

A commit that changes the public API in an incompatible way **must** be flagged
with either:

1. An exclamation mark after the type/scope: `feat(api)!: rename evaluate endpoint`
2. A `BREAKING CHANGE:` footer in the commit body:

```
feat(api)!: rename evaluate endpoint

BREAKING CHANGE: /v1/check is now /v1/evaluate. Update all PEP integrations.
```

Both forms are equivalent and trigger a major version bump in the release
process.

---

## Branching strategy

|    Branch     |                           Purpose                           |
|---------------|-------------------------------------------------------------|
| `main`        | Always releasable. Protected — no direct pushes.            |
| `develop`     | Integration branch for completed features before a release. |
| `feat/<name>` | New features — branch from `develop`.                       |
| `fix/<name>`  | Bug fixes — branch from `develop` (or `main` for hotfixes). |
| `docs/<name>` | Documentation-only changes.                                 |

Branch names should be lowercase and use hyphens: `feat/policy-evaluation-engine`.

---

## Pull request process

1. **Open an issue first** for any non-trivial change. This avoids duplicate
   effort and lets maintainers give early feedback on the approach.
2. Fork the repository and create your branch from `develop`.
3. Write or update tests for your change.
4. Run `./mvnw spotless:apply` to format your code.
5. Run `./mvnw clean verify` and make sure it passes locally.
6. Update documentation (`README.md`, `docs/`) if needed.
7. Open a PR against `develop` (or `main` for hotfixes) using the PR template.
8. At least one maintainer review is required before merging.
9. PRs are squash-merged — the PR title becomes the commit message in `main`.

### What makes a good PR

- Solves exactly one problem. If you find a second issue while working, open a
  separate PR.
- Has a clear description of _why_ the change is needed, not just what it does.
- Includes tests that cover the new behavior (or explains why tests are not
  applicable).
- Is small enough to review in one sitting (aim for < 400 lines changed).

### What slows down review

- No linked issue — reviewers have no context.
- Missing tests or failing CI.
- Code style not applied (CI will catch this anyway).
- Mixing unrelated changes in one PR.

---

## Versioning

This project uses **[Semantic Versioning 2.0.0](https://semver.org)**:
`MAJOR.MINOR.PATCH`.

- `PATCH` — backwards-compatible bug fixes.
- `MINOR` — new backwards-compatible functionality.
- `MAJOR` — incompatible API changes (breaking changes).

While the project is in `0.x`, minor version bumps may include breaking changes.
The API stabilizes at `1.0.0`.

---

## Release process

Releases are fully automated by **[Release Please](https://github.com/googleapis/release-please)**
(`.github/workflows/release-please.yml`). As a contributor, you do not need to
edit `CHANGELOG.md`, bump versions, or create tags manually.

Here is what happens automatically:

1. Every push to `main` that contains a `feat`, `fix`, `perf`, or `BREAKING CHANGE`
   commit triggers Release Please.
2. Release Please opens (or updates) a **release PR** with a draft
   `CHANGELOG.md`, a `pom.xml` version bump, and a `.release-please-manifest.json`
   update.
3. When a maintainer merges the release PR, Release Please creates the Git tag
   (e.g., `v0.2.0`) and publishes a GitHub Release with the generated notes.

**Your only job: use Conventional Commits.** Release Please reads the commit
history to decide the next version and to generate the changelog entries. If
your commit types are correct, the rest is handled automatically.

---

## Questions?

Open a [question issue](https://github.com/ricardoqmd/service-policy/issues/new?template=question.yml)
or reach out to the maintainer directly via GitHub:
[@ricardoqmd](https://github.com/ricardoqmd).
