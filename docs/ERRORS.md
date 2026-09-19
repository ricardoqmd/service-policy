# Error catalog

This service returns errors as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
Problem Details, with media type `application/problem+json`. Every error body
carries the standard members (`type`, `title`, `status`, `detail`) plus two
conventions used throughout this API:

- **`code`** — a short, stable, machine-readable identifier. Switch on this in
  client code; it will not change for a given error class.
- **`type`** — an absolute URI that also anchors into this document. It is the
  human-facing identifier for the same error class.

Per-error extension members (for example `policyId`, `currentRevision`) carry the
machine-actionable context a client needs to react.

One exception: **unauthenticated** requests (`401`) are rejected by the
authentication layer as a `WWW-Authenticate` challenge with **no** problem+json
body. Everything else below is problem+json.

|                               `code`                                | HTTP |                         When                         |
|---------------------------------------------------------------------|------|------------------------------------------------------|
| [`POLICY_ALREADY_EXISTS`](#policy-already-exists)                   | 409  | Creating a policy whose id is taken in this app      |
| [`INVALID_POLICY`](#invalid-policy)                                 | 400  | The policy document failed validation                |
| [`PRECONDITION_REQUIRED`](#precondition-required)                   | 428  | A conditional write arrived without `If-Match`       |
| [`PRECONDITION_FAILED`](#precondition-failed)                       | 412  | The `If-Match` ETag is stale                         |
| [`POLICY_NOT_FOUND`](#policy-not-found)                             | 404  | The referenced policy does not exist                 |
| [`VERSION_NOT_FOUND`](#version-not-found)                           | 404  | The referenced version does not exist                |
| [`FORBIDDEN`](#forbidden)                                           | 403  | The caller is not authorized for this operation      |
| [`CATALOGUE_ENTRY_ALREADY_EXISTS`](#catalogue-entry-already-exists) | 409  | The app already declares that resource type          |
| [`CATALOGUE_ENTRY_NOT_FOUND`](#catalogue-entry-not-found)           | 404  | The app declares no catalogue for that resource type |
| [`ACTION_IN_USE`](#action-in-use)                                   | 409  | Removing an action an active policy still governs    |
| [`APP_CONFIG_ALREADY_EXISTS`](#app-config-already-exists)           | 409  | The app already has a configuration document         |
| [`APP_CONFIG_NOT_FOUND`](#app-config-not-found)                     | 404  | The app has no configuration document                |
| [`INVALID_APP_CONFIG`](#invalid-app-config)                         | 400  | The configuration document failed validation         |
| [`BATCH_TOO_LARGE`](#batch-too-large)                               | 400  | A batch carries more items than this deployment caps |
| [`ACTION_RESOURCE_TYPE_MISMATCH`](#action-resource-type-mismatch)   | 400  | The action's prefix is not the resource's type       |

---

## Policy already exists

`POLICY_ALREADY_EXISTS` · **409 Conflict**

**Meaning.** A policy already exists with the id you tried to create **in this
application**. A policy is identified by `(app, policyId)` (ADR-026), so the same id
in another application is a different policy and is created normally — this conflict
is scoped to the app in the path.

**Triggered by.** `POST /v1/apps/{app}/policies` with a `policyId` that is already
registered *in that app*. Create is not an update; use the write endpoint to add a
new version.

**Client should.** Treat as a naming conflict within the application. Either choose a
different id or, if the intent was to revise the existing policy, switch to appending
a version.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#policy-already-exists",
  "title": "Policy already exists",
  "status": 409,
  "code": "POLICY_ALREADY_EXISTS",
  "detail": "A policy with id 'doc-access' already exists in app 'nami'.",
  "policyId": "doc-access"
}
```

---

## Invalid policy

`INVALID_POLICY` · **400 Bad Request**

**Meaning.** The submitted policy document failed structural or semantic
validation.

**Triggered by.** `POST` or `PUT` of a policy whose body is malformed — a missing
required field, an unknown operator, an ill-formed condition, and so on. It also
covers a body that carries an `app` field: the application is determined by the path
(ADR-026), and a body that could contradict it is rejected rather than reconciled.

**Client should.** Read `invalidParams` and surface each `field`/`reason` to the
author. The list may contain one or more entries.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#invalid-policy",
  "title": "Invalid policy document",
  "status": 400,
  "code": "INVALID_POLICY",
  "detail": "The policy document failed validation.",
  "invalidParams": [
    { "field": "combiningAlgorithm", "reason": "required field is missing" },
    { "field": "rules[0].condition.op", "reason": "unknown operator 'NOPE'" }
  ]
}
```

---

## Precondition required

`PRECONDITION_REQUIRED` · **428 Precondition Required**

**Meaning.** The write is conditional and you did not send the `If-Match` header,
so it was refused rather than applied unconditionally.

**Triggered by.** A mutating request on an existing policy
(`PUT /v1/apps/{app}/policies/{id}`, and the activation endpoints) sent without
`If-Match`.

**Client should.** `GET` the resource, read the `ETag` response header, and retry
the write with `If-Match: "<etag>"`.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#precondition-required",
  "title": "Precondition required",
  "status": 428,
  "code": "PRECONDITION_REQUIRED",
  "detail": "This write requires an If-Match header carrying the current ETag."
}
```

---

## Precondition failed

`PRECONDITION_FAILED` · **412 Precondition Failed**

**Meaning.** Your `If-Match` ETag no longer matches the resource: it changed after
you read it (someone else wrote to it). The write was not applied.

**Triggered by.** A conditional write whose `If-Match` value is stale — and only that.
When a conditional write matches nothing, the service asks the store why: a resource that
is gone is a `404`; one whose revision moved is this `412`; and one stored in a shape this
build reads but may not write (ADR-034) is a server error, never a `412`, because no
`If-Match` a client could send would satisfy it.

**Client should.** Reload the resource (its `ETag` moved to `currentRevision`),
re-apply the intended change on top of the current state, and retry. This is the
lost-update guard: it prevents silently overwriting another author's change.

**Extension members follow one rule.** `currentRevision` is always present — it is what
you need in order to retry, so every conditional write carries it. Beyond that, the body
identifies the target only when the path does not: a write on a **policy** adds
`policyId`, because a policy is identified by `(app, policyId)` and the id is worth
echoing; a write on a resource identified **solely by its request path** — an action
catalogue entry (`(app, resourceType)`) or an application's configuration (`app`) —
carries no identifier member at all, because the path already names it. Nothing is
filled in with a value that is not what the member means.

So there are two body shapes, not one per resource.

A policy write — the only shape with `policyId`:

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#precondition-failed",
  "title": "Precondition failed",
  "status": 412,
  "code": "PRECONDITION_FAILED",
  "detail": "If-Match \"4\" does not match the current revision 6 of policy 'doc-access' in app 'nami'.",
  "policyId": "doc-access",
  "currentRevision": 6
}
```

A path-addressed write — no identifier member. This shape covers **both** the action
catalogue (`PUT`/`DELETE` of `/v1/apps/{app}/action-catalogue/{resourceType}`) and the
application configuration (`PUT`/`DELETE` of `/v1/apps/{app}/configuration`); only the
`detail` sentence differs, naming whichever resource the path addressed:

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#precondition-failed",
  "title": "Precondition failed",
  "status": 412,
  "code": "PRECONDITION_FAILED",
  "detail": "If-Match does not match the current revision 3 of the action catalogue entry for resource type 'document' in app 'nami'.",
  "currentRevision": 3
}
```

**Preconditions come first.** On every conditional write — policy, action catalogue,
configuration — the `If-Match` check runs before the operation's own rules, so a stale
ETag returns this 412 even when the requested change would *also* have been refused on
its merits: with [`ACTION_IN_USE`](#action-in-use) on a catalogue write, or with
[`INVALID_APP_CONFIG`](#invalid-app-config) on a configuration write. A client holding a
stale ETag is reasoning about a resource it has not seen; reloading comes first, and the
other objection may not even survive the reload.

This ordering starts once there is a document to reason about. A request that fails at
the transport boundary — no body, malformed JSON, an unknown field — is a `400` before
any of this, because there is nothing yet to check a precondition against.

---

## Policy not found

`POLICY_NOT_FOUND` · **404 Not Found**

**Meaning.** No policy exists with the referenced id **in the application named in
the path**. Identity is `(app, policyId)` (ADR-026): a policy that exists in another
application is not visible here, and addressing it through the wrong app yields this
404 rather than someone else's policy.

**Triggered by.** Any operation targeting a policy id that is not registered in that
app — for example `PUT`, `GET /v1/apps/{app}/policies/{id}`, or the activation
endpoints.

**Client should.** Verify both the app and the id. A create is required before the
policy can be read or written.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#policy-not-found",
  "title": "Policy not found",
  "status": 404,
  "code": "POLICY_NOT_FOUND",
  "detail": "No policy with id 'ghost' in app 'nami'.",
  "policyId": "ghost"
}
```

---

## Version not found

`VERSION_NOT_FOUND` · **404 Not Found**

**Meaning.** The policy exists, but not the version number you referenced.

**Triggered by.** Referencing a non-existent version — for example activating a
version that was never appended, or `GET /v1/apps/{app}/policies/{id}/versions/{version}`
for a version outside the recorded range. Versions are counted within `(app, policyId)`,
so the same version number can exist in another app's policy of the same id.

**Client should.** List the policy's versions to discover the valid range.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#version-not-found",
  "title": "Version not found",
  "status": 404,
  "code": "VERSION_NOT_FOUND",
  "detail": "Policy 'doc-access' in app 'nami' has no version 7.",
  "policyId": "doc-access",
  "requestedVersion": 7
}
```

---

## Forbidden

`FORBIDDEN` · **403 Forbidden**

**Meaning.** You are authenticated, but not authorized for what you asked. There are
three cases, and the `detail` tells them apart only where doing so discloses nothing.

**Triggered by.**

- **A control-plane call the control-plane policy set does not permit** (ADR-033). Every
  endpoint under `/v1/apps/{app}/policies`, `/policies:simulate`, `/action-catalogue` and
  `/configuration` is decided by policy for the application in the path, from your
  validated token. The body of this refusal is always the same, word for word: it does
  **not** depend on whether the application exists, so it cannot be used to discover
  applications. Before the service is installed, only the bootstrap subject is permitted.
- **Creating or deleting the configuration of the reserved control-plane application**,
  by a caller that is otherwise authorized for it. That configuration is created by
  installation and replaced with `PUT`; it is never created or deleted through the API.
- **A delegated query** — an explicit `subject` different from the caller on `/evaluate`,
  `:enumerate` or `/policies:simulate` — without the delegation marker (ADR-013 §5). On
  `:simulate` it is reached after the control-plane gate has already permitted the read, and
  it depends only on the body.

There is no administrative marker: no role or scope grants control-plane access by
itself (ADR-033 §5).

**Client should.** This is an authorization gap, not an authentication one — do not
re-authenticate. For the control plane, the credential needs the application in the claim
that the **stored** configuration of the reserved control-plane application maps to `apps`
— after installation the deployment property is ignored, so the claim in force is the stored
one. Adding a policy does not widen this on its own: with deny-overrides, every policy
selected for the action must permit it, so an added policy can narrow access but not extend
it (ADR-033 §1), and widening means revising the policy that governs that action. For
delegation, the credential needs the delegation role or scope.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#forbidden",
  "title": "Forbidden",
  "status": 403,
  "code": "FORBIDDEN",
  "detail": "not authorized for this control-plane operation."
}
```

The merged catalogue `GET /v1/policies` never answers this: a caller who may read no
application receives `200` with an empty page, since a `403` would itself disclose that
other applications exist.

---

## Catalogue entry already exists

`CATALOGUE_ENTRY_ALREADY_EXISTS` · **409 Conflict**

**Meaning.** This application already declares an action catalogue for that resource
type (ADR-028). An entry is identified by `(app, resourceType)`, so the conflict is
scoped to the app in the path — the same resource type in another application is a
different, independent entry and is created normally.

**Triggered by.** `POST /v1/apps/{app}/action-catalogue` with a `resourceType` that
is already declared *in that app*. Create is not an update: use `PUT` on the entry to
change its action set.

**Client should.** Treat as a naming conflict within the application. If the intent
was to change the vocabulary, `GET` the entry, read its `ETag`, and `PUT` the new
action set with `If-Match`.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#catalogue-entry-already-exists",
  "title": "Action catalogue entry already exists",
  "status": 409,
  "code": "CATALOGUE_ENTRY_ALREADY_EXISTS",
  "detail": "An action catalogue entry for resource type 'document' already exists in app 'nami'."
}
```

---

## Catalogue entry not found

`CATALOGUE_ENTRY_NOT_FOUND` · **404 Not Found**

**Meaning.** The application declares no action catalogue for that resource type
(ADR-028). Entries are keyed by `(app, resourceType)`, so an entry that exists in
another application is not visible here.

**Triggered by.** `GET`, `PUT` or `DELETE` of
`/v1/apps/{app}/action-catalogue/{resourceType}` for a resource type that app has
never declared.

**Client should.** Verify both the app and the resource type — `GET
/v1/apps/{app}/action-catalogue` lists everything the app declares. A resource type
must be declared before any policy about it can be authored, so this 404 is also the
answer to "why is authoring rejecting my actions?".

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#catalogue-entry-not-found",
  "title": "Action catalogue entry not found",
  "status": 404,
  "code": "CATALOGUE_ENTRY_NOT_FOUND",
  "detail": "No action catalogue entry for resource type 'invoice' in app 'nami'."
}
```

---

## Action in use

`ACTION_IN_USE` · **409 Conflict**

**Meaning.** You tried to remove an action that an **active** policy still governs
(ADR-028). Adding actions to a catalogue is always safe — `*` was expanded when each
policy was authored, so no existing policy changes meaning — but removing one is not,
and a verb an active policy names is not silently deletable. Nothing was written: a
rejected replace is not a partial one.

**Triggered by.** `PUT /v1/apps/{app}/action-catalogue/{resourceType}` whose new
action set drops an action an active policy of that resource type declares, or
`DELETE` of an entry while any of its actions is in that position. Inactive policies
never block: they decide nothing.

**Client should.** Read `policyIds` — it names every active policy standing in the
way. Either deactivate them, or author a new version that no longer governs the verb,
then retry. This is a fail-safe refusal, not a transient error: retrying unchanged
will keep failing.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#action-in-use",
  "title": "Action in use",
  "status": 409,
  "code": "ACTION_IN_USE",
  "detail": "Action(s) [delete] of resource type 'document' in app 'nami' are referenced by active policies [doc-shredder].",
  "policyIds": ["doc-shredder"]
}
```

---

## App config already exists

`APP_CONFIG_ALREADY_EXISTS` · **409 Conflict**

**Meaning.** The application already has a configuration document (ADR-029).
Configuration is a singleton per application — one document, addressed by the path
alone — so creating a second one is not a thing that can mean anything.

**Triggered by.** `POST /v1/apps/{app}/configuration` for an app that is already
configured. Create is not an update.

**Client should.** Switch to `PUT`: `GET` the configuration, read its `ETag`, and
replace it with `If-Match`. That is the only path that carries the lost-update guard,
which is why create does not silently overwrite.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#app-config-already-exists",
  "title": "Application configuration already exists",
  "status": 409,
  "code": "APP_CONFIG_ALREADY_EXISTS",
  "detail": "App 'nami' already has a configuration; use PUT to replace it."
}
```

---

## App config not found

`APP_CONFIG_NOT_FOUND` · **404 Not Found**

**Meaning.** The application has no configuration document (ADR-029). Configuration is
per-app data keyed by the app in the path, so another application's configuration is
not visible here.

**Triggered by.** `GET`, `PUT` or `DELETE` of `/v1/apps/{app}/configuration` for an app
that has never been configured, or whose configuration has been deleted.

**Client should.** `POST` to create one. Note that this is an *administrative* answer
only: an application without configuration evaluates perfectly well — it simply has no
claim mapping and no attribute source, so subject attributes come only from the caller.
Absent configuration withdraws the engine's ability to derive attributes; it never
denies and never widens.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#app-config-not-found",
  "title": "Application configuration not found",
  "status": 404,
  "code": "APP_CONFIG_NOT_FOUND",
  "detail": "No configuration for app 'nami'."
}
```

---

## Invalid app config

`INVALID_APP_CONFIG` · **400 Bad Request**

**Meaning.** The submitted configuration document failed write-time validation
(ADR-029): a missing or out-of-range field, a malformed attribute-source URL, or a
document that configures nothing at all.

**Triggered by.** `POST` or `PUT` of `/v1/apps/{app}/configuration` violating any of:
at least one of `subjectAttributes`/`pip` present; non-blank attribute names and claim
paths; when `pip` is present, all four of its fields, with `url` an absolute
`http`/`https` URL containing the `{sub}` placeholder, `timeoutMs` in 1..10000,
`cacheTtlSeconds` in 0..86400, and a non-blank `credentialRef`.

One more rule applies to the **reserved control-plane application** only (ADR-033 §6): a
`PUT` whose `subjectAttributes`, resolved against the caller's own token, would not give
the caller the reserved application in `apps` is refused with this code, on the field
`subjectAttributes.apps`, and nothing is stored. It does not stop a change from excluding
other callers; it guarantees that whoever makes a change can undo it.

Validation is syntax and bounds only — the configured source is deliberately **never
contacted** at write time. A source that is down when configuration is written is not a
configuration error, and an admin write that dials out is an admin write that can hang.

**Client should.** Read `invalidParams` and surface each `field`/`reason`. Field paths
are dotted (`pip.timeoutMs`, `subjectAttributes.rol`) and every violation in the
document is reported at once, so the whole thing can be fixed in one pass.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#invalid-app-config",
  "title": "Invalid application configuration",
  "status": 400,
  "code": "INVALID_APP_CONFIG",
  "detail": "The configuration document failed validation.",
  "invalidParams": [
    { "field": "subjectAttributes.rol", "reason": "claim path must not be blank" },
    { "field": "pip.url", "reason": "must contain the '{sub}' placeholder for the subject id" },
    { "field": "pip.timeoutMs", "reason": "must be between 1 and 10000" }
  ]
}
```

---

## Batch too large

`BATCH_TOO_LARGE` · **400 Bad Request**

**Meaning.** The `requests` list of a batch evaluation carries more items than this
deployment accepts. The batch is rejected **whole** — never truncated, because a caller
that believes it evaluated N decisions and received fewer is the worst possible contract
for an authorization engine.

**Why this is not `BAD_REQUEST`.** Every other input rejection on this surface is a
programming mistake: the same request never works, wherever it is sent. This one is
different. The cap is deployment configuration, so a batch of a hundred items is correct
against one instance and refused by the next, and a caller that recognises this rejection
can re-chunk and succeed. Sharing a code with the mistakes would make a recoverable
condition indistinguishable from a bug.

**Extension member.** `maxBatchSize` — the cap **this rejection applied**. Use it rather
than a number read elsewhere: configuration can change between two requests, and a client
that mixed the two could re-chunk to a size that is already stale.

**Client should.** Split the batch into chunks of at most `maxBatchSize` and retry. The
same value is also published, before any request is sent, as `evaluation.batchMaxSize` on
`GET /info` — read it at startup to size batches correctly in the first place, and treat
this error as the correction when it changes underneath.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#batch-too-large",
  "title": "Batch too large",
  "status": 400,
  "code": "BATCH_TOO_LARGE",
  "detail": "'requests' must contain between 1 and 100 items.",
  "maxBatchSize": 100
}
```

---

## Action resource type mismatch

`ACTION_RESOURCE_TYPE_MISMATCH` · **400 Bad Request**

**Meaning.** The request names an action whose prefix is not the type of the resource it
asks about (ADR-036). An action is `verb` or `type:verb`, split at the **first** colon: in
`document:read` the prefix is `document` and the verb `read`, and in `a:b:c` the prefix is
`a` and the verb `b:c`. When a prefix is present it must be `resource.type` exactly — no
normalization, no case folding, no aliasing, so `Document:read` asked about a `document`
disagrees. An action with no colon is the verb alone and never disagrees.

The request is refused before anything is evaluated: policies are selected by the verb,
so answering it would mean deciding a question about a resource type the request did not
name.

**Triggered by.** `POST /v1/apps/{app}/evaluate`, `POST /v1/apps/{app}/evaluate/batch`
and the `request` of `POST /v1/apps/{app}/policies:simulate`, when the action carries a
prefix and a verb that is not blank, and the prefix differs from `resource.type` by any
character — whitespace and case included. In a batch, the first disagreeing item refuses
the **whole** batch, and no item is evaluated. Two cases are not this error:

- An action with a prefix and a blank verb (`document:`) is malformed, and is refused
  with `BAD_REQUEST` whatever its prefix: that check runs first.
- A batch item whose `resource.type` is absent or blank has nothing to compare the prefix
  with. This rule does not refuse it; the item is evaluated and answered with a deny.
  (Outside a batch, a blank `resource.type` is refused with `BAD_REQUEST` before this
  rule is reached.)

**Extension members.** `actionPrefix` — what precedes the first colon of the action;
`resourceType` — the request's `resource.type`; and, on a batch only, `index` — the
zero-based position of the offending item in `requests`. They disclose nothing: the
caller sent all of them.

**Client should.** Treat as a programming mistake in the caller: the same request never
works. Most often the action was copied from a neighbouring resource type. Send the action
that belongs to `resourceType` — or the bare verb, which names no type — and fix the item at
`index` before resending a batch.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#action-resource-type-mismatch",
  "title": "Action does not match resource type",
  "status": 400,
  "code": "ACTION_RESOURCE_TYPE_MISMATCH",
  "detail": "requests[1]: action prefix 'document' does not match resource.type 'payment'.",
  "actionPrefix": "document",
  "resourceType": "payment",
  "index": 1
}
```

