# 25. Policy listing shows all lifecycle states — `?status=` filter

- **Status:** Accepted
- **Date:** 2026-07-13
- **Deciders:** Ricardo
- **Refines:** ADR-014/ADR-020 (inactive is a first-class lifecycle state), ADR-017 (collection contract), ADR-024 (`?app=` filter on the same surface)

## Context

`GET /v1/policies` returns only **active** policy heads: the resource, the store and
the repository all filter on `{activeVersion: {$ne: null}}`. The consistency across
the three layers shows this was not an oversight — it was an **implicit decision that
was never taken as one**. The listing surface was designed with production visibility
in mind.

Its real consumer, however, is the **control plane**. The entire `/v1/policies*`
surface is admin-gated (ADR-013), and the first real consumer — the PAP — hit the
consequence immediately: an administrator creates a policy (`POST` → 201, **inactive
by design**, ADR-014/ADR-020), the detail view shows it, but **the list does not**.
The creator does not see their own creation; the policy is only reachable by knowing
its id.

This contradicts the engine's own lifecycle. "Create leaves the policy inactive" plus
"activation is explicit" means **inactive is a first-class working state**, not
residue. A control plane that cannot see the working state cannot administer the
lifecycle it is built to administer.

Note: the **evaluator** (`findActiveByAppAndResourceType`, ADR-021/ADR-024) is
unaffected. The data plane must continue to see only active policies. This decision is
strictly about the administrative listing surface.

## Decision

`GET /v1/policies` gains a **`status`** query parameter with values
`active | inactive | all`, defaulting to **`all`**.

- **Semantics:** `active` = `activeVersion != null`; `inactive` = `activeVersion ==
  null`; `all` = no state filter. This is exactly the structural invariant of ADR-016
  ("active" is a single scalar field), so all three filters are trivial in Mongo.
- **Default `all`, not `active`.** The surface is admin-gated; its reader is an
  administrator whose primary question is "what is the complete state of the world,
  including what is not yet in production." Someone who wants only active policies asks
  for it explicitly. This is a default-behavior change in 0.x, requested by the only
  consumer that exists — the cheapest moment this will ever be.
- **Invalid `status` → 400** problem+json, the same treatment as the existing paging
  validation (`validatePaging`), reusing the existing error code (no new codes).
- `HeadStatus` is modeled as a **domain type**, not a REST or persistence type: the
  lifecycle state of a head is vocabulary of the model, and both the web layer (query
  param) and the persistence layer (filter) need it. Placing it in either layer would
  force one to depend on the other and violate the ArchUnit boundaries (ADR: `rest` and
  `persistence` must not depend on each other).

### Shape of the listing contract (settled here, so it does not have to be re-settled)

`GET /v1/policies` is a listing surface with **additive, optional filters combined with
AND**, expressed as query parameters. Each new filter enters as an independent
parameter without changing the `{data, pagination}` envelope (ADR-017) and without
altering the defaults of the others. There is no query DSL, no separate `/search`
endpoint, and no request body for reads.

`?app=` (ADR-024) and `?status=` (this ADR) are the first two instances. Filters that
the PAP may need later (search by `policyId`, date ranges over `audit.createdAt`) fit
this shape without redesign — they are added when a screen actually needs them, with
their index if the query requires one. They are **not** built preventively.

## Reasons

- **It fixes a contradiction with the engine's own lifecycle.** Inactive is a working
  state by design; the administrative surface must show it.
- **The listing surface is admin-only.** There is no production consumer of it whose
  expectations would be violated by seeing inactive policies; the data plane reads
  through a different path entirely.
- **The default is the fix, not the parameter.** Keeping `active` as the default and
  requiring the PAP to send `?status=all` would leave the trap in place for the next
  consumer, and the compatibility argument is empty with one consumer — the one
  reporting the bug.
- **Trivial to implement, exactly like the invariant it queries.** `activeVersion` is a
  single field; the three filters are `{$ne: null}`, `null`, and no filter.

## Alternatives considered

- **Keep default `active`; the PAP sends `?status=all`.** Rejected: preserves a default
  that hides the creator's own creation, for the benefit of no existing consumer.
- **A separate endpoint for inactive/draft policies.** Rejected: two endpoints for one
  collection differing only by a filter; the pagination envelope and the filters would
  have to be duplicated.
- **Expose the raw `activeVersion` and let clients filter.** Rejected: client-side
  filtering over a paginated collection is broken by construction (a page of results
  cannot be filtered without breaking totals).

### Better option not omitted (and its impact)

- **Add the filters the PAP will foreseeably need now** (search by `policyId`, date
  range over `audit.createdAt`), so the listing screen ships complete in one pass.
  Impact: each has a different cost — `policyId` search is cheap (the unique index
  exists), a date range needs a new index on `audit.createdAt`, and free-text search
  over policy content would need a Mongo text index with its write-time cost. Building
  them before the screen exists means guessing which ones matter and likely getting
  half of them wrong. Deferred, not omitted: the contract shape above guarantees they
  enter without redesign, the moment a PAP screen actually needs them.

## Consequences

- `GET /v1/policies` returns all lifecycle states by default; the PAP sees created
  policies immediately.
- `PolicyHeadRepository` / `PolicyLifecycleStore` gain status-aware find/count; the
  evaluator's query is untouched.
- **Accepted risk:** at scale, a default of `all` can make the default view noisy if
  most policies are inactive (old working versions, abandoned drafts). The answer is
  `?status=active`, one filter away, and the PAP surfaces it as a toggle. If the noisy
  case ever becomes the common one, the default is worth revisiting — but the opposite
  trap (not seeing your own creation) is the worse of the two.
- Combined with `?app=` (ADR-024), the two filters compose with AND, as the contract
  shape above establishes.

## Criteria to revisit

- Inactive policies dominate the collection to the point where the default view is
  routinely unusable → reconsider the default (with data, not anticipation).
- The PAP's list screen needs search or date filtering → add it as an additive query
  parameter under the contract shape above, with its index if required.
- A non-administrative consumer of the listing appears → re-examine whether the default
  should differ per audience (today there is none: the surface is admin-gated).

