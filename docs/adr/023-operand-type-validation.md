# 23. Operand type validation — reject at authoring, deny at evaluation

- **Status:** Accepted
- **Date:** 2026-07-09
- **Deciders:** Ricardo
- **Refines:** ADR-011 (null/unusable operand semantics), ADR-012/014 (authoring/validation), ADR-018 (error contract)

## Context

Ordering operators (`GT`/`GTE`/`LT`/`LTE`) require numeric operands
(`Operator` documents this). When an operand is not numeric, `ConditionEvaluator.
compareNumbers` throws `PolicyTypeException`, which currently surfaces on
`/evaluate` as an unstructured **HTTP 500**. This was found while covering the
evaluator's decision branches (branch-coverage work): a reachable path returns a
server error for what is really a data problem, not a server fault. A 500 on a
reachable route is a leak of the RFC 9457 error contract (ADR-018), and it forces
any client — including the future PAP frontend, which will consume this contract —
to defend against a generic server error instead of a precise, structured signal.

An operand can be a **literal** (written in the policy, e.g. `GT "abc"`) or an
**attribute reference** resolved at request time (e.g. `GT subject.clearance`,
where `clearance` may arrive as a string). The type problem therefore has two
distinct origins that must be handled at two different points in the lifecycle.

## Decision

### (a) Static mistyping → rejected at authoring (422)

A comparison that uses an ordering operator with a **literal** operand that is not
numeric is a malformed policy and is rejected when the policy is written
(`POST /v1/policies`, `PUT /v1/policies/{id}`), not later. Validation happens in the
document mapper that already validates policy structure
(`PolicyDocumentMapper`/`ConditionDocumentMapper`, the source of `INVALID_POLICY`):
when it builds a `Comparison` whose operator is ordering and whose literal operand
is non-numeric, it raises the validation error → **`INVALID_POLICY`** (the existing
ADR-018 code; 400/422 per the catalog). No new error code.

Consequence: a statically mistyped policy never reaches `activate` or `/evaluate`.
By the time a version is activated it is already well-typed; `activate` does not
re-validate types (each point validates its own concern once).

**Authoring, not activation.** Type correctness is a property of the policy itself,
so the earliest point that can decide it — authoring — is where the author (human or
another system via the API) gets the error, in the same request that introduced it.
Deferring to activation would return 201 on create and only fail later at activate,
decoupling the error from its cause; worse DX for a programmatic consumer.

### (b) Dynamic mistyping → deny at evaluation (never 500)

A comparison whose operand is an **attribute reference** that resolves at request
time to a non-comparable value (e.g. a string where a number is required) does not
raise an error. It makes the comparison **not hold** — the condition evaluates to
`false`, exactly as an absent operand does under ADR-011. `compareNumbers` no longer
throws `PolicyTypeException` for this case; the ordering comparison returns `false`.

This is fail-safe by construction: an operand that cannot be compared never
over-permits, and it never turns a request into a server error. Under
deny-overrides with `defaultEffect: deny`, an unusable runtime operand denies.

Consequence: the runtime type mismatch is the **consumer's** data problem, surfaced
as a consistent, safe **deny** — not as a 422 and not as a 500. A consumer testing
its integration observes that a mistyped attribute always denies, and learns the
rule from documented behavior, without the PDP raising errors at decision time.

### Net effect

The reachable HTTP 500 is eliminated in both directions: static mistyping is
rejected at authoring (422 `INVALID_POLICY`), dynamic mistyping denies at evaluation
(ADR-011 extended). `/evaluate` no longer throws on operand type.

## Reasons

- **Each point validates its own concern once.** Authoring validates correctness
  (structure + operand types); activation validates fitness for production;
  evaluation makes decisions. Types are correctness → authoring.
- **Best DX for the real flow.** Policies are often created by another system via
  the API and later activated by rule. Rejecting at create returns the error in the
  request that caused it, not two steps later at activate.
- **`compareNumbers`-as-deny is the ADR-011 rule, generalized.** "Absent operand ⇒
  condition does not hold" and "incomparable operand ⇒ condition does not hold" are
  the same fail-safe principle. Runtime type problems join null under one rule.
- **Removes a contract leak the PAP would inherit.** A structured 422 at authoring
  lets the PAP frontend point the author at the exact malformed condition; a 500
  would force it to handle a generic server error.

## Alternatives considered

- **Validate static types at activation instead of authoring.** Rejected: create
  would return 201 for a malformed policy and the failure would appear later at
  activate, decoupled from the cause — worse DX, and it lets a malformed policy sit
  persisted. Activation is the production gate, not the place to discover authoring
  errors.
- **Return 422 for the dynamic (runtime) case too.** Rejected: a runtime attribute
  type mismatch is not a malformed policy and not a malformed request to the PDP —
  it is the consumer supplying an attribute of the wrong type. Treating it as an
  error at decision time makes `/evaluate` throw on data, which contradicts the
  fail-safe posture of ADR-011. Deny is safer and simpler, and keeps `/evaluate`
  total (never throws on operand type).
- **Leave the 500 and document it.** Rejected: a reachable 500 on a security
  component is a contract leak, and it burdens the PAP frontend that will consume
  this API.

### Better option not omitted (and its impact)

- **A full policy type-checker as a distinct domain component** (validate every
  operator/operand type combination, not only ordering-vs-numeric, possibly with a
  declared attribute schema). Impact: a real type system for the policy language —
  valuable if the operator/type matrix grows, but today the only type constraint is
  "ordering operators need numeric operands," which the mapper can enforce inline.
  Building a general type-checker now is premature (one rule). Deferred: revisit if
  more operator/type constraints appear, or if an attribute schema (ADR-005/010) is
  introduced that lets authoring-time validation also reason about attribute-typed
  operands, narrowing the dynamic case.

## Consequences

- The document mapper gains an operand-type check for ordering operators over
  literal operands → `INVALID_POLICY` at create/append.
- `ConditionEvaluator.compareNumbers` no longer throws on non-numeric operands; the
  ordering comparison returns `false` (deny) for incomparable runtime operands,
  consistent with ADR-011.
- `PolicyTypeException` is no longer thrown from the evaluation path; if it is
  removed, confirm no other caller depends on it.
- `/evaluate` becomes total with respect to operand types: it never returns 500 for
  a type mismatch.
- Behavior documented for consumers (e.g. `docs/ERRORS.md` / policy authoring docs):
  literal type errors are rejected at authoring; runtime type mismatches deny.

## Criteria to revisit

- The operator/type matrix grows beyond "ordering ⇒ numeric" → introduce a real
  policy type-checker (the deferred option above).
- An attribute schema is introduced (declared attribute types) → authoring-time
  validation could also cover attribute-referenced operands, shrinking the dynamic
  (deny) case.
- A consumer needs to distinguish "denied because of a type mismatch" from an
  ordinary deny → consider an evaluation diagnostic/obligation rather than changing
  the decision to an error.

