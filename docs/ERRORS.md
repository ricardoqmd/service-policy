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
| [`FORBIDDEN`](#forbidden)                                           | 403  | The caller lacks the required authorization marker   |
| [`CATALOGUE_ENTRY_ALREADY_EXISTS`](#catalogue-entry-already-exists) | 409  | The app already declares that resource type          |
| [`CATALOGUE_ENTRY_NOT_FOUND`](#catalogue-entry-not-found)           | 404  | The app declares no catalogue for that resource type |
| [`ACTION_IN_USE`](#action-in-use)                                   | 409  | Removing an action an active policy still governs    |
| [`APP_CONFIG_ALREADY_EXISTS`](#app-config-already-exists)           | 409  | The app already has a configuration document         |
| [`APP_CONFIG_NOT_FOUND`](#app-config-not-found)                     | 404  | The app has no configuration document                |
| [`INVALID_APP_CONFIG`](#invalid-app-config)                         | 400  | The configuration document failed validation         |

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

**Triggered by.** A conditional write whose `If-Match` value is stale.

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

**Meaning.** You are authenticated, but you lack the authorization marker the
operation requires (for example the admin marker for authoring, or the delegation
marker for querying a different subject).

**Triggered by.** A call to a protected operation with a valid token that does not
carry the required marker.

**Client should.** This is an authorization gap, not an authentication one — do not
re-authenticate. The identity needs the appropriate role or scope.

```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#forbidden",
  "title": "Forbidden",
  "status": 403,
  "code": "FORBIDDEN",
  "detail": "Admin marker required."
}
```

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

