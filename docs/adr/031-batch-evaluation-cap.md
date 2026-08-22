# 31. Batch evaluation size cap

- **Status:** Accepted
- **Date:** 2026-08-12
- **Deciders:** Ricardo
- **Refines:** ADR-004 (the batch surface), ADR-018 (error contract)

## Context

`POST /v1/apps/{app}/evaluate/batch` accepts an unbounded `requests` list. Every item
costs a policy fetch and an evaluation on the engine's hot path. While the engine ran
only in local development this was theoretical debt; a deployed instance reachable by
real PEPs turns it into the cheapest possible denial of service — one oversized request,
sent by a buggy or misbehaving caller, monopolizes the evaluator for everyone.

The API already bounds every other "how much per request" interaction: collection
listings cap `size` at 100. The batch surface is the one place with no bound at all.

## Decision

### 1. The batch is capped; the default is the API's existing bound

A batch may carry **1 to 100** items by default. 100 is not a new number: it is the same
maximum the API already enforces for collection page size — one consistent answer across
the surface for how much a single request may ask for.

### 2. The cap is deployment configuration, with a fail-fast floor

`service-policy.evaluation.batch-max-size` (typed config mapping, default `100`).
Deployments with different traffic shapes may tune it; a value below 1 fails startup
validation, like the authorization markers do (ADR-013) — a misconfigured engine refuses
to boot rather than refusing all batches at runtime.

### 3. Over-cap and empty batches are input-validation rejections — no new error code

An absent or empty `requests`, or one exceeding the cap, is rejected whole with
**400 `BAD_REQUEST`** (`'requests' must contain between 1 and {max} items.`), exactly as
paging bounds are rejected. There is no partial processing: the batch surface already
fails as a unit when any item is invalid, and a size violation follows the same rule.

## Reasons

- **The engine must bound its own work.** `/evaluate` is consumed east-west by backends,
  not through the gateway; no upstream component can be assumed to protect it.
- **One bound, everywhere.** Reusing the collection maximum keeps the surface coherent
  and the justification internal to this API.
- **Configurable but safe by default.** A typed property with a validated default is the
  boring middle between a hard-coded constant and premature rate-limiting machinery.

## Alternatives considered

- **413 Payload Too Large.** Rejected: the surface's input-bound rejections are 400 with
  the existing code; introducing a second status for the same class of mistake fragments
  the error contract (ADR-018) for no client benefit.
- **Silently truncate to the cap.** Rejected outright: a PEP that believes it evaluated
  N decisions but received 100 is the worst possible contract — silent partial answers
  in an authorization engine.
- **Leave it unbounded and rate-limit upstream.** Rejected: there is no upstream on the
  east-west path, and outsourcing the engine's self-protection couples its safety to
  deployment topology.
- **A fixed constant, not configurable.** Rejected: deployments legitimately differ in
  batch shapes; the property with a safe default costs nothing extra.

### Better option not omitted (and its impact)

- **Per-caller quotas / rate limiting (429).** Real protection against *sustained*
  abuse, not just single-request abuse, with per-client accounting. Deferred because the
  callers are a small, allowlisted, trusted set (the deployment restricts who can reach
  the engine at the network level), so the per-request bound closes the realistic hole.
  Trigger: untrusted or multi-tenant callers, or observed sustained overload.

## Consequences

- `BatchEvaluationRequest` size is validated before any evaluation runs; the OpenAPI
  description documents the bound and names the property.
- New config property with default and startup validation; no new error codes; the
  ERRORS.md `BAD_REQUEST` catalogue entry covers this case as another input bound.
- A future batch simulate (deferred in ADR-027) must adopt the same bound.

## Criteria to revisit

- Untrusted or multi-tenant callers reach the evaluation surface → per-caller quotas
  with 429 (the deferred option above).
- Real batch traffic shows the default materially wrong in either direction → retune the
  default with evidence, not the mechanism.

