# 8. MVP policy domain

Date: 2026-06-23

## Status

Accepted

## Context

The engine currently ships a deterministic stub (`StubPolicyEvaluator`). Phase 2
replaces it with a real evaluator over persisted policies. Before modeling
persistence and the condition AST, we must decide the **domain** the first policies
describe — the example/test policies that ship in this **public, portfolio**
repository (Apache-2.0; see the license/visibility ADR).

Two forces pull in opposite directions:

- We want the MVP to be **representative** enough to de-risk the real first consumer
  (an internal HR pilot), whose canonical case is *"a user may read a record only for
  records in their area or assigned to them, with an emergency override that must be
  audited."*
- The repository is **public**. Modeling real institutional structure (domain nouns,
  real scoping rules) in a public repo is a compliance and exposure risk, and couples
  a deliberately neutral PDP to one domain.

## Decision

Ship a **synthetic, neutral domain** whose **structural shape mirrors the real case**,
with neutral nouns and disposable fixtures.

Domain model:

- `user` (subject): `id`, `role`, `area`
- `document` (resource): `id`, `ownerId`, `area`, `assignees[]`, `sealed`
- action `read`; context `emergency` (boolean)

First slice = **three rules**, `defaultEffect: deny`, `deny-overrides`:

1. `assigned-access` — permit `read` if `subject.id ∈ resource.assignees`
2. `area-scope` — permit `read` if `resource.area == subject.area`
3. `sealed-deny` — deny `read` if `resource.sealed == true`

Boolean permit/deny only. **Obligations and the emergency break-glass rule are
deferred** to the next increment (they require obligation support in the response).

Real consumer policies (real nouns, real scope rules) live in the **private**
consumer repository that calls the same engine — never in this repo.

## Reasons

- Representativeness of **structure**, not of domain: rules 1-3 exercise the ABAC
  surface RBAC cannot (subject-attribute × resource-attribute, ownership/membership,
  deny that overrides permits, default-deny) without exposing any real modeling.
- Iteration speed: a minimal neutral domain keeps the first persistence/AST slice small.
- Compliance and neutrality: nothing institution-specific in a public repo.
- Clean separation: the engine and its examples stay neutral and public; real
  policies stay private.

## Alternatives considered

- **Pure-minimal synthetic** (a single trivial rule): fastest, but doesn't exercise
  deny-overrides or attribute-vs-attribute, so it would not de-risk the real case.
- **Realistic domain** (real-world record/role nouns and scope rules): most
  representative, but models real institutional structure in a public repo — rejected
  on compliance/exposure grounds.
- **Chosen: principled blend** — neutral nouns, real structural shape.

## Consequences

- (+) A solid, representative first slice that is safe to publish and maps 1:1 to the
  real case.
- (+) Establishes default-deny + deny-overrides + attribute comparison as working,
  tested behavior.
- (−) The neutral↔real mapping must be kept in mind (documented in the engine
  explainer, kept out of the policy fixtures).
- (−) Deferring obligations means the break-glass scenario is not yet demonstrable;
  it lands in the next increment.

## Revisit if

- The synthetic domain stops representing a real consumer need (a required rule can't
  be expressed neutrally).
- Obligations/break-glass need to land sooner than planned (pulls rule 4 + obligation
  support forward).

---

Related: contract surface (ADR-004). Combining algorithm (`deny-overrides`) and
immutable policy versioning are established decisions in the consolidated ADR record.
