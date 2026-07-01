# 11. Null operand semantics in condition comparisons

Date: 2026-06-30

## Status

Accepted

## Context

Attribute references resolve against the request's open attribute bags. An absent attribute
resolves to `null` — an unknown *key* inside a known bag returns `null` (not an error); only an
unknown path *prefix* fails loudly. The comparison operators originally handled null operands
inconsistently:

- `EQ` used `Objects.equals`, so two absent attributes (`null EQ null`) evaluated to `true` — an
  over-permit. A document with no `area` and a subject with no `area` satisfied an `area-scope`
  permit rule.
- `NEQ` had the mirror problem: an absent attribute was "not equal" to any literal, so a
  `NEQ`-based rule matched on absence.
- `NOT_IN` against an absent collection was vacuously `true`.
- Ordering operators threw `PolicyTypeException` on a null operand, so an absent numeric
  attribute produced a 500 rather than simply not matching.

These are latent authorization holes: absence accidentally granting access, or a deny rule
accidentally not firing.

## Decision

A **null operand makes a comparison not hold** (returns `false`), uniformly across `EQ`, `NEQ`,
`IN`, `NOT_IN`, and the ordering operators. An absent attribute therefore never satisfies a
comparison.

A **present operand of the wrong type** is unchanged — it remains a policy-authoring error and
still fails loudly: ordering operators throw `PolicyTypeException` on a present non-numeric
operand, and `NOT_IN` against a present non-collection keeps its prior vacuously-true behaviour.
The rule distinguishes *absence* (null → no match) from *type error* (present but wrong → loud).

## Reasons

- **Absence should never grant.** With default-deny, a permit rule that references an attribute
  now requires it present and matching; if absent, the rule does not apply and evaluation falls
  through to deny. Fail-safe in the common (permit) direction; closes the `area-scope` over-permit.
- **Consistent and predictable.** One rule for all operators ("absent ⇒ no match") is easier to
  reason about than per-operator null quirks.
- **No 500s on absent attributes.** Ordering operators no longer throw when an attribute is simply
  missing; absence is a normal ABAC condition, not an error.

## Alternatives considered

- **Indeterminate (three-valued, XACML-style):** a null comparison yields a third value that
  propagates through `AND`/`OR` and the combining algorithm. Resolves the deny-direction gap
  automatically but requires three-valued logic throughout — disproportionate for the MVP.
- **A dedicated `PRESENT` operator:** keep null ⇒ false and let authors handle absence explicitly
  when needed (e.g. `OR(NOT PRESENT x, x NEQ "y")`). Complementary, not a replacement; add it when
  a real rule needs it ("promote at second consumer").
- **Effect-dependent (null ⇒ false in permits, null ⇒ deny in deny rules):** maximally fail-safe
  but implicit — the same comparison means different things depending on the rule's effect.
  Rejected: conflicts with "explicit over implicit".

## Consequences

- (+) Closes the absent-attribute over-permit class (`EQ null==null`, `NEQ`-on-absent,
  `NOT_IN`-on-absent) and removes 500s from absent numeric attributes.
- (+) Permit rules are fail-safe: a missing required attribute denies, never grants.
- (−) **Authoring discipline for deny rules.** A deny rule written *exclusively* (e.g.
  `DENY when subject.attr.clearance NEQ "topsecret"`) does not fire when the attribute is absent,
  so it cannot *force* a requirement. Guidance:
  - Put requirements in the **permit** rule with `EQ`/`IN` over the positive value (e.g.
    `PERMIT when AND(subject.id IN assignees, subject.attr.clearance EQ "topsecret")`); an absent
    attribute then fails the `AND` and denies.
  - Use **deny** rules only for "present and bad" (e.g. `DENY when resource.attr.sealed EQ true`,
    `DENY when subject.attr.status EQ "revoked"`), never `NEQ`/`NOT_IN` to enforce presence.
  - `NEQ`/`NOT_IN` remain correct over operands that are **always present** (e.g. `action`,
    `resource.type`, PEP-guaranteed `context.*`).
- (−) Slight divergence from XACML's Indeterminate handling; acceptable for the MVP.

## Revisit if

- A real deny rule must act on the *absence* of an attribute → add a `PRESENT` operator
  (alternative 3).
- Full XACML fidelity becomes a requirement → reconsider three-valued Indeterminate
  (alternative 2).
