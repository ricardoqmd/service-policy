# 27. Policy simulation — dry-run evaluation against an unsaved document

- **Status:** Accepted
- **Date:** 2026-07-14
- **Deciders:** Ricardo
- **Builds on:** ADR-021 (evaluator reads active heads), ADR-013 (admin gate), ADR-023 (authoring-time validation), ADR-026 (app in path)

## Context

The PAP has a **policy tester**: an administrator assembles a hypothetical case
(action + resource + subject attributes + context) and sees what the PDP would decide.
Today it can only test against the **active** version, because
`/v1/apps/{app}/evaluate` reads `activeContent` from the head (ADR-021). An inactive
version — or a draft being edited — is invisible to the evaluator, which is correct for
the data plane (nothing un-activated should decide production access) but leaves the
control plane unable to answer the question that gives a tester its value: **"does this
version I just edited do what I think, before I put it into production?"**

Testing an already-persisted version and testing a draft-not-yet-saved look like the
same feature but are not. The important one is the draft: the real authoring loop is
*edit → test → adjust → test → and only then save the good version*. If the only way to
test is against a saved version, an administrator must persist throwaway versions just
to try them — and versions are append-only (ADR-016), so those failed experiments
remain in the history forever. A tester that forces you to pollute the append-only log
to use it is the wrong tester.

## Decision

Add an **admin-gated simulation endpoint** that runs the engine against a **policy
document supplied in the request**, without persisting anything and without touching
active state.

```
POST /v1/apps/{app}/policies:simulate
body: {
  "policy":  <policy document>,     // the same shape as a create body (no `app`)
  "request": <evaluation request>   // the same shape as an evaluate body (no `app`)
}
→ 200 Decision   (the same Decision returned by /evaluate)
```

- **One mechanism covers both cases.** To test a draft, the PAP sends the document it is
  editing. To test an existing version, the PAP sends that version's content (which it
  already holds from a GET). There is no separate "simulate version N" endpoint — the
  caller supplies the content either way, so a query-param variant against a stored
  version would be redundant surface.
- **The document is validated exactly as on create.** The supplied `policy` goes through
  the same `PolicyDocumentMapper.fromDocument` path that create/append use, so a
  malformed document, a bad operand type (ADR-023), or a body-carried `app` (ADR-026) is
  rejected with the same `INVALID_POLICY` / `BAD_REQUEST` it would get on create —
  *before* any evaluation runs. The simulator never evaluates a document that could not
  have been created. This is a correctness and safety requirement, not a nicety: the
  engine must never run logic that would not survive authoring validation.
- **Zero effect.** No write to `policy_heads` or `policy_versions`, no change to
  `activeVersion`, no decision audit persisted. It is a pure function of
  `(policy, request)`.
- **Admin-gated (ADR-013).** It is an administration tool, so it uses the admin marker,
  not the ordinary evaluation gate. A non-admin caller gets 403.
- **App from the path (ADR-026).** Both the `policy` and the `request` are scoped to the
  path's app; neither carries `app` in its body. Isolation (ADR-024) is intact by
  construction — the simulation only ever sees the one document supplied.
- **Same `Decision` response**, so the PAP reuses its existing render.

### Naming

The endpoint uses a **sub-resource action** (`:simulate` suffix on the collection),
distinguishing a non-CRUD operation from the resource routes without inventing a new
top-level path. It is a verb on the app's policy collection: "simulate a decision in
this app."

## Reasons

- **The draft case is the one that matters**, and only an inline-document endpoint serves
  it. A query-param-against-a-stored-version design (the cheaper alternative) cannot test
  an unsaved draft, so it would force throwaway versions into the append-only history.
- **The machinery is already there.** The decision core (`PolicySelector` + `PolicyEngine`)
  is already a pure function over `(List<Policy>, request)`; only candidate retrieval
  touches Mongo. Simulation feeds the engine the supplied policy instead of the active
  heads — a thin addition, not a parallel evaluator. And `PolicyDocumentMapper.fromDocument`
  is already the complete authoring validator that create/append call, so "validate as on
  create" reuses it directly rather than duplicating rules.
- **One endpoint, both cases.** Supplying content inline subsumes "test an existing
  version": the caller has that content already. Two endpoints would be redundant.

## Alternatives considered

- **(a) Query param on evaluate** (`POST …/evaluate?policyId=X&version=N`): evaluate the
  request against a stored version. Cheapest — reuses the evaluator, only changes where
  the content is read from. Rejected as the primary design: it cannot test a draft, which
  is the case that justifies the tester; it would push administrators to save throwaway
  versions to try them, polluting the append-only history.
- **(b) Dedicated per-version simulate route**
  (`POST …/policies/{id}/versions/{v}/simulate`): explicit, but same limitation as (a) —
  only stored versions — plus more routes. Rejected.
- **(c) inline document — chosen.** Most capable (drafts *and* stored versions), at the
  cost of running create-grade validation outside the create path. Accepted because that
  validation is reused, not duplicated, and the cost is the correct one to pay.

### Better option not omitted (and its impact)

- **A batch simulate** (several requests against one candidate document in a call),
  mirroring `/evaluate/batch`, so the tester can run a suite of cases against a draft in
  one round trip. Impact: useful for a "test table" UI, but no consumer needs it yet and
  it widens the surface. Deferred, not omitted: it is a straightforward extension of this
  endpoint (a list of requests instead of one) if the PAP's tester grows a case-suite
  view. The trigger is a real multi-case tester screen.

## Consequences

- New admin-gated endpoint `POST /v1/apps/{app}/policies:simulate`; no change to the
  data-plane `/evaluate`.
- The authoring validation (`fromDocument`) gains a second caller; if it was not already
  free of persistence side effects, that must be confirmed (it is a pure Map→Policy
  transform today).
- No new error codes: malformed documents reuse `INVALID_POLICY`, bad requests reuse
  `BAD_REQUEST`.
- The evaluator's decision core is now exercised from two entry points (active-head
  evaluation and simulation); it must stay a pure function of `(policies, request)` — no
  hidden dependency on persistence in `PolicySelector`/`PolicyEngine`.
- The PAP's tester gains a version/draft selector with no redesign: default "active"
  (the existing `/evaluate`), or "simulate" with the edited/selected content.

## Criteria to revisit

- The tester needs to run many cases against one draft → add a batch simulate (the
  deferred option above).
- A need arises to simulate against the *combination* of several policies (not one
  document) → revisit whether simulation should accept a set, closer to how the engine
  combines active heads.
- Simulation must record an audit trail (e.g. "who tested what") → decide whether a
  simulation audit is written, kept distinct from decision audit.

