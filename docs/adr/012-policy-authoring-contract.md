# 12. Policy authoring contract (POST /v1/policies)

Date: 2026-06-30

## Status

Accepted

## Context

The engine evaluates persisted policies, but until now a policy could only enter storage by
in-code seeding. The Policy Administration Point (PAP) — and any operator running the service —
needs to author policies over HTTP. This ADR fixes the request contract and the create semantics
for the first authoring endpoint.

## Decision

`POST /v1/policies` accepts a policy in the **same document shape used for persistence**:
`policyId`, `version`, `resourceType`, `actions[]`, `combiningAlgorithm`, `defaultEffect`, and
`rules[]`, where each rule is `{id, effect, condition}` and a condition is a recursive AST of
`{"type": "comparison"|"and"|"or"}` with operands `{"ref": "<path>"}` or `{"value": <literal>}`.
The body is mapped to the domain via the shared `PolicyDocumentMapper` — authoring and storage use
one validated format.

Create semantics (each the minimal correct choice):

1. A created policy is **active immediately**.
2. A **duplicate `policyId` is rejected with 409 Conflict** — `POST` creates; replacing is a future
   `PUT`.
3. **Structural validation is delegated to the mapper** — a malformed body yields **400** with the
   mapper's message.

Authoring **requires a Bearer token** (401 otherwise), consistent with the other endpoints.

## Reasons

- Reusing the document shape means one format and one validated mapper, with zero drift between what
  you author and what is stored and evaluated.
- Active-on-create, 409-on-duplicate and mapper→400 are each the smallest correct choice that does
  not preclude the fuller CRUD later.

## Alternatives considered

- **A distinct, typed request DTO with a hand-written DTO→domain mapping:** rejected — duplicates the
  proven document mapper and adds a second format to keep in sync. A typed OpenAPI schema can be
  layered on later without changing the wire format.
- **Create-inactive plus an explicit activate step:** deferred — belongs with the full CRUD
  (activate/deactivate/versioning).
- **Upsert on duplicate id:** rejected — `POST` creates; replacing is `PUT` (explicit over magic).

## Consequences

- (+) A policy can be authored and evaluated entirely over HTTP — the first brick of the PAP.
- (+) One shared, validated format across author / store / evaluate.
- (−) **Authoring is authenticated but not authorized.** Any valid Bearer can create a policy; there
  is no admin-role check yet (roles arrive with real JWT validation, ADR-003 Phase 3). Until then the
  authoring endpoint MUST NOT be exposed outside a trusted network. Recorded as a gap.
- (−) Only **create** is implemented; list/get/update/activate/versioning are future increments (the
  full CRUD the PAP will consume).
- (−) OpenAPI shows the body as a generic object (the recursive AST is not a typed schema yet); the
  `.http` examples and this ADR document the shape.

## Revisit if / follow-ups

- Add the rest of the CRUD (list/get/update/activate) — the PAP needs it.
- Add admin-role authorization once real JWT validation lands (ADR-003).
- Consider a typed OpenAPI schema for the AST if tooling requires it.

