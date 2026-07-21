# 30. Permission enumeration — three-valued evaluation without an instance

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Ricardo
- **Refines:** ADR-004 (`/permissions` surface), ADR-011/ADR-023 (fail-safe operand rule), ADR-013 (subject resolution)
- **Depends on:** ADR-028 (action catalogue), ADR-029 (per-app configuration)

## Context

`/v1/apps/{app}/permissions` has been a stub returning an empty list since ADR-004. Three
consumer applications now need it to condition their UI, and the client SDK cannot be built
against an endpoint whose contract is not closed.

The consumer side clarified a distinction that unblocks the design. "ABAC cannot enumerate"
is only half true — there are two families of decision, and the UI needs both by different
paths:

- **Type-level.** The policy only reads subject attributes, resource *type*, action and
  environment: *"a capturista may create a document."* This **is** enumerable: every
  `(resourceType, action)` pair can be evaluated with no instance in hand. It governs menus,
  sections and "New" buttons — most of the UI's conditional rendering.
- **Instance-level.** The condition reads attributes of the concrete resource: *"may edit a
  document if `document.areaId ∈ subject.areaIds`."* This is **not** enumerable and never
  will be, in any ABAC engine: answering it requires the document. Enumerating it would mean
  the PDP reading each application's business data — the coupling the whole design avoids.

Enumeration is therefore possible for a well-defined subset, and the honest contract must
say which pairs fall outside it rather than silently omitting them.

Two preconditions are now in place: ADR-028 gives a declared vocabulary of
`(resourceType, action)` pairs to enumerate over, and ADR-029 lets the engine derive subject
attributes per application (claim mapping, and later an attribute source) without a
redeploy.

## Decision

### 1. Transport: `GET`, subject from the token, self only

```
GET /v1/apps/{app}/permissions
Authorization: Bearer <token>
```

The subject is the `sub` of the validated token (ADR-013). Subject attributes are derived
per application under ADR-029 — from mapped claims, and from the configured attribute source
where one exists. No body, no query parameters, no caller-supplied attributes.

A subject may only request **its own** permissions. Asking for another subject's permissions
is a different question with a different authorization model (an administration screen
listing what someone else can do); it belongs to a separate endpoint and a separate policy,
not to a flag on this one.

**This does not weaken ADR-010.** ADR-010 fixes that the engine does not invent subject
attributes: it takes them from the caller, or — since ADR-029 — from sources the operator
configured for that application. Reading a claim whose name an operator declared is not the
engine learning an identity provider; it is the same mechanism ADR-013 already uses for the
admin marker, widened from one claim to several. What remains prohibited is hard-coding any
provider's structure.

### 2. Advisory, never enforcement

The response is a **hint for rendering**, not an authorization decision. Enforcement remains
the PEP calling `/evaluate` with attributes it trusts — resource attributes read from its own
database, never from the client. A UI that hides a button it should not have hidden is a
usability bug; a backend that skips `/evaluate` because `/permissions` said yes is a security
bug. The contract states this explicitly so that no consumer treats it as a gate.

### 3. What is enumerated, and the third outcome

For every `(resourceType, action)` in the application's catalogue (ADR-028), the engine
evaluates the applicable active policies **with no resource instance**. Three outcomes:

| Outcome | Meaning | Reported as |
|---|---|---|
| Deterministic **deny** | No instance can change it | **omitted** from the list |
| Deterministic **permit** | No instance can change it | included, `conditional: false` |
| **Indeterminate** | Depends on the instance | included, `conditional: true` |

Including the indeterminate case is the point of the design. Omitting it would hide controls
the user can in fact use for some resources — a silent false negative, invisible to whoever
wrote the policy. Marking it tells the client precisely what it needs: *render the control,
and ask per instance before acting.*

```jsonc
{
  "app": "kronia",
  "subject": "…",
  "permissions": [
    { "resourceType": "document", "action": "create", "conditional": false },
    { "resourceType": "document", "action": "update", "conditional": true,
      "dependsOn": ["areaId", "clasificacion"] }
  ],
  "generatedAt": "2026-07-19T…Z"
}
```

Structured pairs, not `"resource:action"` strings: a client that wants a string can build
one, but a client that receives a string cannot reliably split it.

### 3.1 `dependsOn` — which resource attributes the client must supply

A conditional pair carries the **resource attribute names** whose absence left the decision
undetermined. Without it, a client building an instance-level check has to know, out of band,
which attributes to put in the `/evaluate` request — it must read the policy, or ask whoever
wrote it. That is human coupling with a silent failure mode: when the policy changes from
`areaId` to `ownerId`, the client keeps sending `areaId`, every decision denies, and nothing
reports an error.

With `dependsOn` the client is generic:

```js
resource: { type, id, attributes: pick(row, entry.dependsOn) }
```

A policy change updates `dependsOn`, and the client adapts without a code change. This is the
opposite of coupling to policy internals: it removes the client's need to know the policy at
all.

Two limits keep it honest:

- **Only `resource.attr.*` names.** Subject attributes are the engine's business, resolved
  from the token and the configured source; the client never supplies them and never learns
  about them here.
- **Only the references that actually caused indeterminacy**, collected during the
  three-valued evaluation itself — each `INDETERMINATE` reports the reference it could not
  resolve. Branches already settled contribute nothing, so a client is never asked for
  attributes that would not change the outcome.

What this exposes is a set of attribute names of resources the caller already sees in its own
application's responses. It reveals no operator, no value, no effect, and no rule structure.

### 4. How indeterminacy is computed: three-valued evaluation, in an isolated mode

The engine evaluates the policy set with the resource absent, under **three-valued logic**: a
comparison whose operand cannot be resolved without an instance yields `INDETERMINATE`
instead of a boolean. Propagation is the usual Kleene three-valued semantics, and combination
extends deny-overrides:

- any applicable policy resolving to **DENY** → deny (the pair is omitted);
- otherwise any **INDETERMINATE** → conditional;
- otherwise **PERMIT** → conditional `false`.

Note the ordering: a deterministic deny wins over an indeterminate permit, and an
indeterminate *deny* prevents a permit from being reported as certain. Under deny-overrides
an unresolved policy can only take a permit away, never grant one, so reporting it as
conditional is the honest answer.

This reuses the evaluator rather than adding a separate static analyser of the condition
tree. Propagation handles compound conditions correctly for free: an `OR` whose type-level
branch already permits is not conditional, because the boolean short-circuits before the
indeterminate branch matters — a result a syntactic "does it mention `resource.attr`?" check
would get wrong.

**The critical constraint.** This is a *second* evaluation semantics inside the engine, and
it contradicts the fail-safe rule everywhere else: ADR-011 and ADR-023 make an unresolvable
operand yield **false** (deny), precisely so absence never over-permits. Here absence yields
INDETERMINATE. Wiring this mode into the enforcement path would turn a policy that must deny
into one that does not — the most dangerous defect this component could ship.

Therefore the separation is **structural, not conventional**: the three-valued mode is a
distinct entry point that cannot be reached from `/evaluate` or `/evaluate/batch`, the
enforcement path has no parameter or flag that selects it, and the engine's public decision
type remains two-valued. The build enforces this (an ArchUnit rule, and a test asserting that
the enforcement path returns deny for an unresolvable operand). The indeterminate value never
escapes the enumeration path: it is translated to `conditional` at the boundary and never
appears in a `Decision`.

### 5. Caching and invalidation

The response depends on the subject's attributes, the application's active policies, its
catalogue and its configuration — so `policyVersion` alone is not a sound cache key: a
subject whose attributes change (a new assignment, for instance) would keep a stale answer
while policies are untouched.

The response therefore carries an **ETag derived from the computed result**, and clients
revalidate with `If-None-Match` → `304 Not Modified` when nothing changed. That is correct by
construction regardless of which input moved, and it costs one evaluation per revalidation
rather than a transfer.

A short server-side TTL bounds how stale an attribute change may be. Push invalidation over a
socket is deliberately **not** in v1: it requires an authenticated channel with reconnection
handling, and the cost of a stale hint is bounded — the pair is advisory, and enforcement
re-checks. Revisit when propagation latency is a real complaint.

## Reasons

- **It unblocks three consumers with one call per session.** Menus, sections and create
  buttons resolve from a single request; only the instance-dependent pairs cost a second
  call, and only for the collection being rendered.
- **The third outcome is what makes it honest.** A two-state answer would silently hide
  usable controls; `conditional` is exactly the information the client needs to decide
  whether to ask per instance.
- **Reusing the evaluator beats analysing the AST.** Three-valued propagation gets compound
  conditions right by construction, where a syntactic check would need to re-implement the
  logic and would still get `OR` wrong.
- **`GET` with the token keeps the client simple.** Any SPA, any framework, any language: one
  authenticated request, cacheable by HTTP. No backend-for-frontend is required — an
  application may still put one in front, but the engine does not oblige it.

## Alternatives considered

- **`POST` with caller-supplied `subjectAttributes`.** Preserves the strictest reading of
  ADR-010 and needs no claim mapping. Rejected: a browser client does not know its own
  attributes — it would have to obtain them from the token (which is the mapping, indirectly)
  or from a backend (which is a BFF), and the remaining possibility, a client asserting its
  own `area`, is not acceptable even for an advisory answer. It also forces every consumer to
  run a backend for what is a read.
- **A BFF resolves it; the SPA never talks to the PDP.** Robust, and it keeps the engine
  unaware of tokens beyond identity. Rejected as a *requirement*: it makes a backend-for-
  frontend mandatory for every consumer, closing the engine to clients that do not have one.
  It remains perfectly available to consumers that want it.
- **Omit indeterminate pairs.** Simplest contract, two states. Rejected: silent false
  negatives, and the client loses the signal that would tell it to ask per instance.
- **Static AST analysis to decide conditionality.** Avoids a second evaluation semantics —
  a real advantage given the risk described above. Rejected because it is less accurate: it
  cannot see that a satisfied type-level branch of an `OR` already settles the result, so it
  would over-report conditionality, and re-deriving that would mean re-implementing the
  evaluator's logic in a second place, which is its own hazard.
- **`policyVersion` as the cache validator.** Cheap. Rejected: unsound, because subject
  attributes are an input the policy version knows nothing about.

### Better option not omitted (and its impact)

- **A full explanation mode**: return, per pair, *which policy and which rule* produced the
  outcome — not just the attribute names, but the decision path (`policy doc-access, rule
  area-scope, undetermined at resource.attr.areaId`). Impact: it would turn the PAP into a
  genuine authoring aid — an administrator could see why a permission is denied or
  conditional without mentally simulating the policy set, which is the single most useful
  thing a policy editor can offer. It is not taken now because it exposes policy structure
  (identifiers, rule names, evaluation order) to whatever client calls the endpoint, and this
  surface is called by UI clients with no administrative standing. Doing it properly means a
  separate, admin-gated explain endpoint — closer in spirit to ADR-027's simulation than to
  this one — with its own decision about how much internal structure an answer may carry.
  Trigger: the PAP's editor needs to explain outcomes to authors, at which point it belongs
  with the simulation surface rather than here.

## Consequences

- `/v1/apps/{app}/permissions` stops being a stub; it depends on ADR-028's catalogue and
  ADR-029's configuration being present for the application. Without them the answer degrades
  honestly: no catalogue, nothing to enumerate; no configuration, fewer resolvable attributes
  and therefore more pairs reported as conditional.
- The engine gains a three-valued evaluation mode, isolated from enforcement by construction
  and by build-breaking rules. `Decision` stays two-valued.
- Responses carry an ETag over the computed result and honour `If-None-Match` (304).
- Cost is one evaluation per catalogue pair per request; the catalogue bounds it, and the
  ETag makes revalidation cheap.
- Consumers get a documented two-path model: this endpoint for type-level questions,
  `/evaluate[/batch]` for instance-level ones, with `conditional` telling them which is which
  and `dependsOn` telling them what to send.
- The three-valued evaluation must record, per indeterminate outcome, the resource references
  it could not resolve — a small addition to the enumeration path, and the source of
  `dependsOn`. It is collected during evaluation, never by scanning the condition tree.

## Criteria to revisit

- The PAP needs to explain *why* a pair is denied or conditional to policy authors → the
  explanation mode above, as an admin-gated surface alongside ADR-027's simulation.
- Propagation latency of policy or attribute changes becomes a real complaint → push
  invalidation as a v2 of the caching decision.
- An administration screen must list another subject's permissions → a separate endpoint with
  its own authorization, never a parameter on this one.
- The catalogue grows large enough that evaluating every pair per request is measurable →
  revisit incremental computation or per-pair caching.
