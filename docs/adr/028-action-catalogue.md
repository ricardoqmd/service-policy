# 28. Action catalogue per application, and wildcard expansion at authoring

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Ricardo
- **Refines:** ADR-008 (policy domain), ADR-012 (authoring contract), ADR-024/ADR-026 (app scoping)
- **Enables:** the permissions enumeration surface (its own ADR)

## Context

A policy declares `actions` — the verbs it governs — and may declare `["*"]` to mean
"every action on this resource type". Two problems follow from there being **no catalogue
of actions anywhere in the engine**.

**1. `*` is not enumerable.** The control plane needs to answer "what can this subject do
in this application?" for menus, sections and create buttons. That answer is a list of
`(resourceType, action)` pairs. With `*` there is no list: the engine does not know which
verbs exist, only which ones happen to be spelled out in some policy. Enumeration is
impossible without a declared set of actions.

**2. `*` silently widens existing policies.** Because `*` is resolved at evaluation time
against whatever verbs the caller sends, a policy written as `["*"]` when the vocabulary
was `create/read/update` will, the day someone starts sending `delete` or `export`, permit
those too. Nobody re-reads two hundred policies before introducing a verb. A rule that
grows its own reach without an authoring decision is the exact opposite of the fail-safe
posture the engine holds everywhere else (ADR-011, `defaultEffect: DENY`,
deny-overrides).

Actions are not free-floating: they belong to a resource type. `create`/`update` belong to
`document`; `approve`/`reject` would belong to `request`. A catalogue keyed only by
application would mix vocabularies that have nothing to do with each other.

## Decision

### 1. The action catalogue is a first-class, administrable resource, keyed by `(app, resourceType)`

Each application declares, per resource type, the set of actions that exist:

```
kronia / document  → [create, read, update, delete]
kronia / request   → [create, read, submit, approve, reject]
```

It is administered through the PDP's admin surface (and therefore by the PAP), not through
deployment configuration — a new application, or a new verb, must not require redeploying
the engine.

- Admin-gated (ADR-013), scoped by app in the path (ADR-026).
- An action identifier is an opaque, stable token (ADR-005): the catalogue holds ids, not
  display text. Labels are the PAP's business.

### 2. `*` is expanded at authoring, never stored, never evaluated

When a policy is created or a version appended with `actions: ["*"]`, the engine expands it
against the catalogue of that `(app, resourceType)` **at that moment** and persists the
explicit list:

```
POST /v1/apps/kronia/policies   { "resourceType": "document", "actions": ["*"], … }
persisted:                      { "resourceType": "document",
                                  "actions": ["create","read","update","delete"], … }
```

`*` is input sugar. It never reaches storage and never reaches the evaluator.

Consequences that follow directly:

- **Adding a verb to the catalogue changes no existing policy.** Widening an existing
  policy requires authoring a new version — an explicit, audited act, which is what the
  append-only model exists for.
- Enumeration becomes well defined: the set of `(resourceType, action)` pairs to evaluate
  is the catalogue.
- The PAP can show the author exactly what `*` will become before saving.

### 3. Authoring validates actions against the catalogue

A policy declaring an action that is not in the catalogue of its `(app, resourceType)` is
rejected at authoring with `INVALID_POLICY` (the ADR-023 mechanism; no new error code). A
policy cannot govern a verb the application does not claim to have.

An empty catalogue for a resource type therefore blocks authoring for it — which is
correct: declare the vocabulary first, then write policies about it. `*` against an empty
catalogue is likewise rejected rather than silently producing an empty action list.

## Reasons

- **Fail-safe over convenience.** The engine never widens a decision without an authoring
  act. Expansion-at-authoring converts a silent, deferred widening into an explicit,
  versioned one.
- **Enumeration needs a vocabulary.** The permissions surface is a list of
  `(resourceType, action)` pairs; that list has to come from somewhere declared, not
  inferred from whatever verbs happen to be spelled in policies.
- **Keyed by resource type because that is where verbs live.** It mirrors the policy shape
  (`resourceType` + `actions`) and keeps unrelated vocabularies apart.
- **Administrable, not deployed.** A multi-application engine whose onboarding requires a
  redeploy defeats its own purpose (ADR-024/ADR-026 made applications first-class; their
  configuration must be too).

## Alternatives considered

- **Derive the catalogue from active policies** (the union of actions actually declared).
  No new resource, no administration. Rejected: it cannot express a verb that exists in the
  application but has no policy yet — precisely the case the UI needs (a button whose
  permission is currently denied to everyone still has to be enumerable). It also makes the
  vocabulary a side effect of policy authoring rather than a declaration.
- **Keep `*` as a runtime wildcard and document the risk.** Zero work. Rejected: it leaves
  the silent-widening hole open and leaves enumeration impossible. Both are the reason this
  ADR exists.
- **Expand `*` at evaluation against the current catalogue.** Keeps `*` visible in the
  stored policy, which some would call more expressive. Rejected: it is exactly the silent
  widening — the stored policy's meaning changes when the catalogue changes.
- **A catalogue keyed by app only** (a flat verb list per application). Simpler storage.
  Rejected: it mixes vocabularies of unrelated resource types and makes `*` expand to verbs
  that make no sense for the resource in question.
- **Fold the catalogue into the per-app configuration resource** (one document per app
  holding claim mappings, PIP settings and actions). Rejected: different audiences and
  cadences — the catalogue is authored by whoever designs the application's domain and
  changes as features ship, while platform configuration is set once by an operator.
  Separate resources keep their permissions and their change history separate.

### Better option not omitted (and its impact)

- **A full resource-type registry**, declaring resource types with their actions *and* the
  attribute schema each type exposes (`document` has `areaId`, `ownerId`, …). Impact: it
  would let authoring-time validation catch attribute typos and mistyped comparisons
  against a declared schema — a real strengthening of ADR-023, which today can only check
  literals — and would let the PAP offer attribute autocompletion instead of free text. It
  is deferred because it is a substantially larger surface (schema definition, versioning
  of the schema itself, migration when a type gains an attribute) and because the action
  catalogue alone unblocks enumeration, which is the pressing need. Trigger: authoring-time
  attribute validation becomes a real pain point, or the PAP editor needs attribute
  discovery.

## Consequences

- New admin-gated resource for the catalogue under `/v1/apps/{app}/…`, administered by the
  PAP; new collection.
- Authoring gains two behaviours, `*` expansion and action validation, and they live at the
  single write door — `PolicyLifecycleStore.create`/`append`, which is what guarantees no
  unexpanded or uncatalogued policy can be stored by any caller — plus the simulate path,
  since ADR-027 defines simulation as validate-exactly-as-create. The document mapper stays
  pure: structure and operand types only (ADR-023), no catalogue awareness. No new error code.
- Stored policies no longer contain `*`. Existing development-stage data with `*` should be
  recreated; there is no production data to migrate.
- The evaluator no longer honours `*` at match time either: action matching is literal
  membership. A policy stored with `*` is therefore **inert** — it matches no verb, so it is
  never selected, and an unselected policy contributes nothing: neither its rules nor its
  `defaultEffect`. Inert is *not* fail-closed. In particular, under deny-overrides a legacy
  wildcard DENY would no longer suppress another policy's permit for the same
  `(resourceType, verb)`, because it is not among the candidates being combined; the effective
  decision can therefore move from deny to permit. This is accepted rather than mitigated: there
  is no pre-ADR-028 policy data anywhere at this stage — no production, no development dataset
  worth preserving — and after this ADR the write door cannot produce a stored `*`. A database
  that contains one implies writes made outside the engine, which is outside its trust boundary;
  such a database is to be recreated, as this ADR already requires. No migration and no
  match-time compatibility shim.
- Adding a verb to the catalogue is safe by construction — it affects no existing policy.
  Removing one is not, and needs a decision: a verb still referenced by an active policy
  must not be silently deletable (see revisit criteria).
- The enumeration surface can now be specified, since the set of pairs to evaluate is
  defined.

## Criteria to revisit

- Deleting an action that active policies still reference → decide the deletion contract
  (reject while referenced, or soft-deprecate). Left open here deliberately: the safe
  default until then is to reject deletion of a referenced action.
- Applications need to share a common vocabulary (the same verbs across apps) → consider
  whether a shared base catalogue is worth the coupling, given ADR-024's isolation stance.
- Attribute-level validation is needed at authoring → the resource-type registry above.

