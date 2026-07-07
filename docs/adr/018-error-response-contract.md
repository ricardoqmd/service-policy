# 18. Error response contract (RFC 9457 problem+json) and conditional writes

- **Status:** Accepted
- **Date:** 2026-07-07
- **Deciders:** Ricardo
- **Refines:** ADR-012 (authoring contract), ADR-014 (lifecycle/CRUD — concurrency transport), ADR-017 (response contract — deferred error shape)

## Context

Through Phase 3 the service exposes a single, minimal error shape — a flat
`ApiError{error, message}` object — across `/evaluate`, `/permissions`, and the
read endpoints. ADR-017 explicitly deferred a richer error contract to "its own
ADR when the write endpoints land." They are landing: the write slice adds
`POST /v1/policies` conflicts, append-only `PUT /v1/policies/{id}` with optimistic
concurrency, and (next) `activate`/`deactivate`. These introduce error conditions
the flat shape cannot express well:

- A create against an existing id, a write against a missing policy, and a
  document that fails validation all collapse into the same opaque
  `{error, message}` with no machine-stable discriminator beyond an ad-hoc string.
- A validation failure can only report the first problem (fail-fast), which is a
  poor authoring experience for the PAP frontend.
- Optimistic concurrency (ADR-014) needs to tell a client *what* it conflicts
  with (the current state), not just that it failed.

Deciding this is not scoped to writes: leaving reads and `/evaluate` on the old
shape while writes use a new one would mean two error grammars in one API. The
decision therefore covers the **entire error surface**.

A second, coupled question surfaced: how the client expresses "I am writing based
on the state I last read" for optimistic concurrency. ADR-014 chose a `baseVersion`
value carried in the request body (→ 409). That token is version-scoped: it only
detects a concurrent *append*, not a concurrent activation/deactivation, which
change the head without adding a version. As the write surface grows to include
activation (S3), a single concurrency token that covers *all* head mutations is
preferable to two.

## Decision

### 1. Adopt RFC 9457 `application/problem+json` across the whole error surface

Every error response (400/403/404/409/412/428) is a Problem Details object with
media type `application/problem+json`, replacing the flat `ApiError`. This applies
to reads, `/evaluate`, `/permissions`, and writes alike — one grammar.

Each problem carries the standard members plus two conventions:

- `type` — an absolute, dereferenceable URI identifying the error class, resolving
  to human documentation at `docs/ERRORS.md#<code-kebab>`.
- `code` — a short, stable, machine-readable enum member (e.g. `VERSION_CONFLICT`),
  provided as an extension member so clients switch on `code` rather than parsing
  the `type` URI.
- `title`, `status`, `detail` per the RFC. Per-case extension members (e.g.
  `policyId`, `currentRevision`) carry the machine-actionable context.

Example:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
```
```json
{
  "type": "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#policy-already-exists",
  "title": "Policy already exists",
  "status": 409,
  "code": "POLICY_ALREADY_EXISTS",
  "detail": "A policy with id 'doc-access' already exists.",
  "policyId": "doc-access"
}
```

### 2. Produce problem+json centrally

Domain and persistence raise typed exceptions (`PolicyAlreadyExistsException`,
`PolicyNotFoundException`, `VersionConflictException`, `PreconditionRequiredException`,
`PolicyValidationException`, …). A small set of JAX-RS `ExceptionMapper`s (or one
mapper over a common `ProblemException` base) renders them to the wire shape. REST
resources do not construct error bodies; they let typed exceptions propagate. This
gives one rendering site (consistency, single coverage point) and keeps the write
resources readable.

### 3. Optimistic concurrency via conditional requests (`If-Match` / ETag)

Mutating operations on an existing policy (`PUT /{id}`; and, in S3, `activate`/
`deactivate`) use HTTP conditional requests instead of a body-carried token:

- `GET` on a policy returns a **strong ETag** whose value is the head's `revision`
  (ADR-016): `ETag: "4"`.
- The mutation must send `If-Match` with that ETag. On a match the write proceeds
  and the response carries the new ETag.
- Missing `If-Match` → **428 Precondition Required** (`PRECONDITION_REQUIRED`):
  the write is refused, never applied unconditionally.
- Stale `If-Match` → **412 Precondition Failed** (`PRECONDITION_FAILED`), with the
  current `revision` in an extension member so the client can reload.
- `baseVersion` is removed from the request contract. The `PUT` body is
  `{ "content": { … } }` only.

`revision` (not the active version number) is the ETag value because it advances
on *every* head mutation — append, activate, deactivate — so one token guards all
writes uniformly through S3.

`POST` create is **not** conditional: it has no prior resource to be stale against.
Its only conflict is a duplicate id → 409 `POLICY_ALREADY_EXISTS`.

### 4. Case → status → code map (full surface)

| Case | HTTP | `code` | Extension members |
|---|---|---|---|
| Create duplicate id | 409 | `POLICY_ALREADY_EXISTS` | `policyId` |
| Invalid document (POST/PUT) | 400 | `INVALID_POLICY` | `invalidParams[]` |
| Write missing `If-Match` | 428 | `PRECONDITION_REQUIRED` | — |
| Write with stale `If-Match` | 412 | `PRECONDITION_FAILED` | `policyId`, `currentRevision` |
| Write to missing policy | 404 | `POLICY_NOT_FOUND` | `policyId` |
| Activate missing version (S3) | 404 | `VERSION_NOT_FOUND` | `policyId`, `requestedVersion` |
| Admin marker absent | 403 | `FORBIDDEN` | — |
| Unauthenticated | 401 | *(OIDC challenge, no body)* | — |

`invalidParams` is a list of `{ field, reason }`. The contract is an array from day
one; emitting more than one entry (accumulating all validation errors instead of
failing fast) is a later enhancement that does not change the shape.

### 5. 401 stays an OIDC-layer challenge

`401` continues to be emitted by `quarkus-oidc` as a `WWW-Authenticate` challenge
with no problem+json body. Only authorization failures the application owns (`403`)
migrate to problem+json. The split is intentional: authentication is a transversal
protocol concern, authorization is application logic.

### 6. Document the error catalog

`docs/ERRORS.md` lists every `code` with an anchor matching its `type` URI: what it
means, what triggers it, and how a client should react. This makes `type` URIs
resolvable without hosting infrastructure (they point at the repo blob).

## Reasons

- **One grammar, whole surface.** Two error shapes in one API is a defect; migrating
  now (≈5 shapes in 3 places) is far cheaper than after S3 adds more endpoints.
- **Standard over bespoke.** RFC 9457 is understood without reading our docs, is
  signalled by its media type, and its extension members let us add machine context
  without leaving the standard — boring over clever, and appropriate for a public
  repo.
- **`code` alongside `type`.** Switching on a short enum is more robust client code
  than matching a long URI; the redundancy is deliberate and cheap.
- **Central production.** Typed exceptions + mappers keep the growing write surface
  from scattering error construction and drifting in shape; single coverage point.
- **`revision`-based ETag.** A single conditional token guards append, activate, and
  deactivate uniformly; `If-Match`/412/428 is the HTTP-native mechanism for exactly
  this, and we control both ends (PDP and PAP), so the header discipline costs us
  little and buys lost-update protection plus safe retries.

## Alternatives considered

- **Keep the flat `ApiError`.** Simplest, zero migration. Rejected: cannot express
  field-level validation or concurrency context, and forces bespoke conventions as
  cases grow.
- **Custom `{error:{code,details[]}}` envelope.** Full control, clean nesting.
  Rejected: reinvents a standard (RFC 9457) with no offsetting benefit, and every
  consumer must learn it.
- **`baseVersion` in the body → 409 (ADR-014's original transport).** Simpler,
  form-friendly, already decided, no headers. Rejected in favour of `If-Match`
  because it is version-scoped: it detects concurrent appends but not concurrent
  activation/deactivation, so S3 would need a second token. A single `revision`
  token via `If-Match` covers all writes.
- **Strict 9457 purism (`type` only, no `code`).** More standards-pure. Rejected:
  parsing/switching on URIs is worse client ergonomics than a short `code`.

### Better option not omitted (and its impact)

- **True write idempotency via `Idempotency-Key`.** Conditional requests give
  optimistic concurrency and at-most-once *effect* on retry (a duplicate write hits
  412), but not textbook idempotency (a retry does not return the original success).
  A dedicated `Idempotency-Key` header with server-side dedup would. Impact: a keyed
  request store with TTL and replay of the original result. Deferred as YAGNI for a
  human-driven PAP, where a 412 "someone changed this, reload" is the desired UX.
  Revisit if automated clients with aggressive retries consume the write API.
- **Accumulate all validation errors.** Emitting every `invalidParams` entry at once
  is the better authoring UX. Impact: refactor `PolicyDocumentMapper.fromDocument`
  from fail-fast to an accumulating (Notification-style) validation. Deferred; the
  array contract already supports it, so adoption is non-breaking. Revisit when the
  PAP form makes one-error-at-a-time painful.
- **Dedicated GitHub Pages error site** instead of `docs/ERRORS.md` blob anchors.
  Nicer canonical URLs. Impact: Pages setup and build. Deferred; blob anchors resolve
  today with zero infrastructure.

## Consequences

- All non-401 error responses change shape and media type; this is a breaking change
  to the error contract, applied while the service is pre-1.0 (0.1.x) and deliberate.
- The S1 read endpoints must now emit `ETag` (from `head.revision()`) on single-
  resource `GET`s, so write clients can obtain the token — an additive change to
  `PolicyResource`.
- New typed exceptions and `ExceptionMapper`s are introduced; existing inline error
  construction (`create`) moves to this path.
- `docs/ERRORS.md` becomes part of the public contract and must stay in sync with the
  `code` set; hygiene applies (no institutional terms in `type`, `detail`, or the
  catalog).
- 412/428 handling becomes required in every write client (the PAP).

## Criteria to revisit

- An automated (non-human) write client appears → reconsider `Idempotency-Key`.
- The PAP form UX suffers from fail-fast validation → adopt error accumulation.
- The API is exposed to third-party HTTP clients that would benefit from a hosted,
  canonical error documentation site → promote `docs/ERRORS.md` to Pages.
- A write operation appears whose concurrency scope genuinely differs from "any head
  mutation" → reconsider a per-operation token instead of the single `revision` ETag.
