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

|                      `code`                       | HTTP |                        When                        |
|---------------------------------------------------|------|----------------------------------------------------|
| [`POLICY_ALREADY_EXISTS`](#policy-already-exists) | 409  | Creating a policy whose id is taken in this app    |
| [`INVALID_POLICY`](#invalid-policy)               | 400  | The policy document failed validation              |
| [`PRECONDITION_REQUIRED`](#precondition-required) | 428  | A conditional write arrived without `If-Match`     |
| [`PRECONDITION_FAILED`](#precondition-failed)     | 412  | The `If-Match` ETag is stale                       |
| [`POLICY_NOT_FOUND`](#policy-not-found)           | 404  | The referenced policy does not exist               |
| [`VERSION_NOT_FOUND`](#version-not-found)         | 404  | The referenced version does not exist              |
| [`FORBIDDEN`](#forbidden)                         | 403  | The caller lacks the required authorization marker |

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

