# 017 - REST response contract: collection envelope, pagination, error shape

- **Date:** 2026-07-07
- **Status:** Accepted
- **Deciders:** Ricardo Quintero
- **Relates to:** 012 (policy authoring contract), 013 (endpoint authorization), 014 (lifecycle/CRUD contract — introduces the read/list surface this contract serves)

## Context

The first slice of the ADR-014 lifecycle adds the initial read surface on
`/v1/policies`: a list of active policy heads (`GET /v1/policies`), a single head
(`GET /{id}`), a list of versions (`GET /{id}/versions`), and a single version
(`GET /{id}/versions/{v}`).

Until now every response was a single bare object — `PolicyCreated`,
`PermissionsResponse`, `BatchEvaluationResult`, `Decision` — and every 4xx used a
flat error body, `ApiError { error, message }`, produced consistently across
`PolicyResource`, `EvaluateResource`, `PermissionsResource`, and `AuthContext`.
There is no list endpoint yet, therefore no collection shape and no pagination
precedent. The first list endpoint will set that precedent whether or not the
choice is deliberate.

This ADR fixes the response conventions **once**, before the first list endpoint
calcifies them, so every future list endpoint (and the administration UI that
consumes them) shares one shape. It is a contract decision, not an implementation
detail.

## Decision

1. **Collections are wrapped** in an envelope:
   `{ "data": [ ... ], "pagination": { ... } }`.
2. **`pagination`** is `{ page, size, totalPages, totalElements }`, with `page`
   **1-indexed**. Query parameters: `page` (default `1`) and `size`
   (default `20`, valid range `1..100`; out of range → `400`).
   `totalPages = ceil(totalElements / size)` (`0` when empty).
3. **Single resources stay bare** — no envelope — consistent with the already
   shipped `PolicyCreated` / `PermissionsResponse`. `GET /{id}` and
   `GET /{id}/versions/{v}` return the resource object directly.
4. **Lean-by-default list projection.** A list item is a summary that omits the
   heavy policy content (the rule AST). `?view=full` opts into embedding the full
   content per item. Detail endpoints (`GET /{id}`, `GET /{id}/versions/{v}`)
   always return full content.
5. **Error contract is unchanged.** All 4xx produced by application code keep the
   flat `ApiError { error, message }` shape. A `401` originating in the OIDC layer
   keeps its standard bearer challenge (no application error body). A richer error
   shape (machine code + field-level `details[]`, or RFC 7807 Problem Details) is
   **deferred** to its own ADR, to be applied uniformly when the validation-heavy
   write endpoints land.
6. A **bounded** collection that never needs paging MAY return `{ "data": [ ... ] }`
   with no `pagination` object; a paginated collection MUST include `pagination`.
   (In this slice every collection is paginated.)

## Reasons

- **The envelope earns its place only on collections.** It carries collection-level
  metadata (paging) that a single resource does not have. Bare singles avoid
  gratuitous nesting and stay consistent with shipped endpoints. Explicit over
  implicit.
- **Page-based paging fits an administration UI.** The Policy Administration Point
  renders page numbers; page/size is offset paging parameterized by page
  (`offset = (page - 1) * size`), which is what that client wants to bind to.
- **Lean-by-default bounds list payloads.** Policy content is a full condition AST;
  shipping N of them on every page load to render names and states is waste.
  Full content is opt-in (`view=full`) or a detail view.
- **Reusing the flat error body avoids fragmenting a shipped public contract.** A
  richer shape is a real future need, but a half-migrated error contract (some
  endpoints flat, some nested) is worse than either; it must be uniform, so it
  waits for its own decision.
- **Stable over comprehensive.** Fix a minimal uniform contract now; do not invent
  filters or fields (status filter, links, `details[]`) that have no consumer yet.

## Alternatives considered

- **Envelope everything, singles included, in `{ data }`.** Rejected: it would
  either break the already-shipped bare `PolicyCreated` / `PermissionsResponse`
  (a public-API breaking change) or fragment the surface (some bare, some
  wrapped). The single-unwrap-path benefit does not outweigh breaking shipped
  endpoints from inside a reads-only slice.
- **Cursor pagination (`hasMore` + opaque cursor), no count.** Rejected for now:
  cheaper (no `count()` query) and free of insert drift, but it denies the
  administration UI the page numbers it wants, and adds opaque-cursor handling to
  every client before any list is large enough to need it. Retained as the first
  revisit trigger.
- **Adopt a nested `{ error: { code, details } }` / RFC 7807 now.** Rejected for
  this slice: breaking and out of scope. Reads emit only `401` / `403` / `404`,
  none of which need field-level detail. Deferred to a dedicated ADR when writes
  exist to shape it against real validation cases.
- **`status=active|inactive|all` filter on the list now.** Deferred: no draft
  (inactive) policies exist until the create/versioning slice, so the filter would
  ship with no consumer. Trigger named below.

## Consequences

- (+) One collection shape for all list endpoints; the client has a single
  unwrap-and-paginate path.
- (+) Shipped single-resource endpoints are untouched; no migration.
- (+) Predictable list payloads (lean default; AST only on demand).
- (−) `totalElements` / `totalPages` cost one `count()` per list request. This is
  off the evaluation hot path (admin reads only) and acceptable.
- (−) Page-based paging can drift under concurrent inserts (an item shifts pages
  between two reads). Acceptable for a low-churn policy administration list.
- (−) An asymmetry to internalize: collections are wrapped, singles are bare. It is
  intentional and documented, not accidental.
- The error contract is frozen as-is until a dedicated error-shape ADR; new
  endpoints must not introduce a competing error body.

## Criteria to revisit

- **Cursor pagination** — if a list grows large enough that deep-offset paging or
  insert drift becomes a real problem, or a consumer needs stable streaming, switch
  that endpoint to cursor + `hasMore`. Do not convert preemptively.
- **Error shape** — when the first validation-heavy write endpoint (`PUT` /
  `activate`) needs field-level error detail, open a dedicated ADR, evaluate
  RFC 7807 vs. a nested `{ error: { code, details[] } }`, and migrate **all**
  endpoints uniformly in one breaking bump — never piecemeal.
- **`status` filter on the list** — when the administration UI must render draft
  (inactive) policies; lands with the authoring slice.

## Better option (impact if taken)

- **Cursor pagination from day one**, instead of page/size + count, is the
  strongest alternative. Impact if taken now: it removes the per-list `count()`
  and the insert-drift caveat and scales to large lists without deep-offset cost —
  but it denies the administration UI the page-number UX it currently asks for and
  forces opaque-cursor handling into every client before any list is large enough
  to justify it. Because these admin lists are low-cardinality and page numbers are
  a real UX ask, page/size is the recommended path; cursor is documented here as
  the first thing to reach for when a list outgrows it. Named, not omitted.
- **Adopting the richer error shape (or RFC 7807) now and uniformly** would avoid a
  future breaking bump. Impact: it is the "do the migration once, early" play — but
  designing the field-level `details[]` contract before any endpoint needs it means
  guessing at validation cases that do not exist yet, which tends to guess wrong.
  Deferring until writes land trades one known future bump for a contract shaped by
  real cases. Recommended to defer; recorded here so it is not forgotten.

