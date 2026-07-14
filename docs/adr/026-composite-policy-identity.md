# 26. Composite policy identity `(app, policyId)` and app-nested routes

- **Status:** Accepted
- **Date:** 2026-07-13
- **Deciders:** Ricardo
- **Completes:** ADR-024 (application scoping)
- **Supersedes (in part):** ADR-024 §5 (`?app=` filter), ADR-012/ADR-014 (route shape of the authoring surface)

## Context

ADR-024 made `app` the scoping dimension: each application got its own `resourceType`
namespace, and evaluation selects by `(app, resourceType, verb)`. But **`policyId`
stayed global**. The de-globalization stopped halfway.

The first real use of the PAP surfaced the consequence immediately:

1. Create policy `doc-access` in app `nami` → 201.
2. Create policy `doc-access` in app `kronia` → **rejected**:
   `"app is immutable; policy 'doc-access' belongs to app 'nami'"`.

Two applications that do not coordinate resource-type names are nonetheless forced to
coordinate **policy names** — a coupling ADR-024 exists to remove.

**The error message is the strongest evidence that the model is wrong.** Creating a
policy in `kronia` was interpreted as an attempt to *mutate* the policy of `nami` — an
intent the user never had. It was not even an honest `POLICY_ALREADY_EXISTS`. When the
error describes an action the user did not take, the identity key is wrong.

After ADR-024, the natural identity of a policy is the composite key
**`(app, policyId)`**. Identity must follow scoping.

## Decision

### 1. Identity is `(app, policyId)`

A policy is identified by its application **and** its id. `doc-access` in `nami` and
`doc-access` in `kronia` are two different policies that coexist.

- Unique indexes become `policy_heads (app, policyId)` and
  `policy_versions (app, policyId, version)`.
- `POLICY_ALREADY_EXISTS` (409) now means "already exists **in this app**".
- The ADR-024 immutability check on `app` remains, but only where it actually applies:
  the **append** path (a new version of an existing policy cannot change its app). It no
  longer fires on create, where it was misdiagnosing a legitimate new policy.
- `version` is unchanged in meaning (the append-only version counter); it is simply
  unique **within `(app, policyId)`** rather than within `policyId`.

### 2. The whole v1 surface is nested under the application

`app` is not an incidental request attribute — it is the coordinate that determines
which universe of policies exists for a call. It therefore lives in the **path**, on
both planes:

```
/v1/apps/{app}/policies                       POST (create) · GET (list)
/v1/apps/{app}/policies/{id}                  GET · PUT (append)
/v1/apps/{app}/policies/{id}/activate         POST
/v1/apps/{app}/policies/{id}/deactivate       POST
/v1/apps/{app}/policies/{id}/versions         GET
/v1/apps/{app}/policies/{id}/versions/{v}     GET
/v1/apps/{app}/evaluate                       POST
/v1/apps/{app}/evaluate/batch                 POST
/v1/apps/{app}/permissions                    GET
```

**The path is the single source of the scope.** Consequently:

- **`app` is removed from every request body**: from the policy document
  (`POST`/`PUT`) and from `EvaluationRequest` (including each batch item).
- **Sending `app` in the body is a client error → 400 `INVALID_POLICY`** (authoring) /
  `BAD_REQUEST` (evaluation), with a message stating that `app` is determined by the
  path. It is *not* silently ignored and it is *not* accepted-if-equal: a body field
  that could contradict the path is a class of bug that must not be representable.
- **`app` IS returned in responses** — `PolicyHeadSummary`, `PolicyHeadView`,
  `PolicyVersionSummary` keep the field. The server supplies it; clients render it.

### 3. One cross-app read-only listing survives

```
GET /v1/policies        — administrative cross-app catalogue, READ-ONLY
                          filters: ?app= , ?status= , paging (unchanged envelope)
```

This is **not** the nested collection under another route: it is a different resource
with a different purpose — the catalogue an administrator who supervises several
applications reads. All writes and all per-app reads go through the nested routes; this
endpoint exists because a multi-app administrator needs one paginated, server-side
merged view, which N nested calls cannot provide (paginating a client-side merge of
independently paginated collections is broken by construction).

Its `?app=` filter (ADR-024 §5) survives **only here**; on the nested list it is
meaningless and is removed.

## Reasons

- **Identity follows scoping.** ADR-024 made `app` the scoping axis; leaving `policyId`
  global left the model internally inconsistent and forced cross-app name coordination.
- **The path tells the truth.** With `(app, policyId)` as the identity, a flat route
  `/v1/policies/{id}` would be a lie — `{id}` alone identifies nothing. A required
  query parameter (`?app=`) would be worse: identity smuggled into a slot conventionally
  used for optional filters.
- **One mental model across the whole surface.** Scoping reads the same way for
  administration and for evaluation. The alternative ("app is in the path… except for
  evaluate, where it is in the body") is exactly the kind of exception consumers forget.
- **The cheapest possible moment.** Three consumer applications are integrating now and
  none has shipped. Changing the PEP contract twice this week costs nothing; changing it
  once next year with three applications in production is a coordinated migration.
- **Ambiguity is removed by construction.** With `app` only in the path, a request can no
  longer state one app in the route and another in the body.

## Alternatives considered

- **Flat routes with a required `?app=`** (`/v1/policies/{id}?app=nami`). Cheapest
  change. Rejected: it repeats ADR-024's own mistake one layer up — identity left
  half-expressed. A *required* query parameter is a contract smell; query params are
  conventionally filters, not identity.
- **Nest only the administration surface, keep `app` in the evaluation body.** Defensible
  (the control plane addresses resources; the data plane passes context), and it avoids a
  second PEP contract change. Rejected on the strength of the uniformity argument: `app`
  is not an incidental attribute like `context.emergency` — it determines which policies
  exist at all. Putting the scoping axis in the same slot as circumstantial attributes
  disguises it. With zero integrated consumers, uniformity wins over churn avoidance.
- **Keep `policyId` globally unique and document the coordination requirement.**
  Rejected: it is precisely the cross-application coupling ADR-024 removed, and the
  error message it produces misdescribes the user's action.
- **Nested routes with no cross-app listing at all (pure nesting).** Cleanest possible
  model. Rejected: a multi-app administrator would lose the unified, server-paginated
  view; N nested calls cannot be paginated coherently in the client.

### Better option not omitted (and its impact)

- **Make the cross-app catalogue scope itself to the caller's apps** (return only the
  policies of the applications the caller may administer, from the `apps` subject
  attribute). Impact: this is per-app *administrative authorization*, which ADR-024
  deliberately kept outside the engine — the write gate is admin-binary and the PAP is
  the guardian (meta-policy `resource.attr.app IN subject.attr.apps`). Implementing it in
  the PDP would mean the engine learning an organizational model (against ADR-001) or
  reading a claim it does not otherwise trust. Deferred, not omitted: today the PAP
  filters the catalogue for the administrator; if another holder of the admin marker ever
  appears, the engine needs defense in depth here **and** on the write path — one
  decision, taken together (revisit criterion of ADR-024).

## Consequences

- **Breaking contract change on both planes.** Every route moves under
  `/v1/apps/{app}/…`; `app` leaves every request body. All three consumers must adopt it
  before integrating. `GET /v1/policies` (cross-app, read-only) is the single exception.
- Unique indexes are rebuilt as `(app, policyId)` and `(app, policyId, version)`.
- `POLICY_ALREADY_EXISTS` regains its honest meaning (duplicate id *within an app*), and
  the `app`-immutability error stops firing on legitimate creates.
- Evaluation logic is untouched: selection was already `(app, resourceType, verb)`; only
  the transport of `app` changes (path instead of body).
- Development-stage data has globally unique ids, so no migration is required.
- No new error codes: the "app in body" rejection reuses `INVALID_POLICY` /
  `BAD_REQUEST`.

## Criteria to revisit

- A consumer needs to address a policy without knowing its app (e.g. a global audit tool
  resolving by id alone) → revisit whether the cross-app catalogue needs a by-id lookup.
- Administrative scope must be enforced by the engine rather than by the PAP → the
  cross-app catalogue and the write gate get per-app authorization together (see the
  deferred option above and ADR-024's revisit criteria).
- A resource other than policies becomes app-scoped → `/v1/apps/{app}/…` is already the
  established shape for it.

