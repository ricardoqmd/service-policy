# 32. Permission enumeration under pushed subject attributes

- **Status:** Accepted
- **Date:** 2026-08-22
- **Deciders:** Ricardo
- **Refines:** ADR-030 (transport only — its three-valued model and advisory stance are unchanged)
- **Depends on:** ADR-010 (caller-asserted attributes), ADR-013 §5 (hybrid subject provenance), ADR-026 (app in the route), ADR-029 (per-app configuration)

## Context

ADR-030 shipped `GET /v1/apps/{app}/permissions`: the subject is the `sub` of the
validated token, and subject attributes are derived from that token's claims through the
per-app mapping of ADR-029. That works while the attributes a policy needs live in the
token.

They no longer do. The consuming institution's multi-contract model puts the authorization
role outside the identity provider: a person holds several concurrent contracts, the role
is granted per `(contract, application, environment)`, and the active contract is a
**choice made per session by the client**, not a fact about the subject. The decided
channel is push — the application's backend resolves the attributes for the active
contract and asserts them on the request, exactly as ADR-010 describes. `/evaluate` already
accepts them: `EvaluationRequest.subjectAttributes` is that bag.

`/permissions` cannot. It is a `GET` with, by its own decision, *"no body, no query
parameters, no caller-supplied attributes."* So the two surfaces now resolve subject
attributes through **different channels, and only one of them was fed**:

|             Surface              |     Attribute channel     | Works when roles live outside the token |
|----------------------------------|---------------------------|-----------------------------------------|
| `POST /v1/apps/{app}/evaluate`   | caller-asserted (ADR-010) | yes                                     |
| `GET /v1/apps/{app}/permissions` | claim mapping (ADR-029)   | no                                      |

The consequence is not a failure — ADR-029's degradation holds and the endpoint returns
*more* pairs as `conditional`, never fewer, so nothing is wrongly permitted. But a menu in
which every entry is "it depends" carries no information, and enumeration exists precisely
to carry that information. The endpoint stays safe and stops being useful.

This blocks the client SDK and every consuming application's UI: the enumeration surface
they were waiting on cannot answer for a role it cannot see.

### Why the browser is not the caller

Worth stating, because the shape of the fix follows from it. Three independent reasons the
enumeration call belongs to the application's backend, two of which predate this context:

1. **The endpoint is advisory, not enforcement** (ADR-030 §2). Every action is re-decided at
   `/evaluate` by the backend with attributes it trusts. The backend was never off the path.
2. **`/evaluate` trusts what its caller asserts** (ADR-010). It shares a host and a port with
   `/permissions`; reachability is one decision, not two. A PDP a browser can reach is a PDP
   whose evaluation surface accepts `{"rol": ["<admin>"]}` from anyone. The deployment's
   access control is network-level, and it depends on that.
3. **A browser cannot be trusted to assert its own role** — the new one, and the reason the
   attributes must be pushed by something that resolved them from the system of record.

None of this costs the client anything: it still gets one call and one payload; the URL is
its own backend's rather than the engine's.

## Decision

### 1. A second transport for the same computation

```
POST /v1/apps/{app}/permissions:enumerate
Authorization: Bearer <the calling PEP's token>

{
  "subject": "8b1f-…",
  "subjectAttributes": { "rol": ["3f2a-…"], "contractId": "c-8f21" }
}
```

The response body, status codes, `ETag`, `Cache-Control` and the `conditional` /
`dependsOn` semantics are **identical to the `GET`**. `PermissionsView`,
`PermissionEntry`, `EnumerationEvaluator` and the three-valued computation are untouched:
`EnumerationEvaluator.enumerate(app, subjectId, subjectAttributes)` already takes the
attribute map as a parameter. Only the transport decides where that map comes from.

The `:enumerate` sub-resource verb follows `POST /v1/apps/{app}/policies:simulate`
(ADR-027): a read whose input does not fit in a URL. `POST` on the collection path itself
is not used — it reads as creation, and this creates nothing.

The `GET` **remains**, unchanged and un-deprecated. A deployment whose attributes do live in
the token keeps the simpler call; the engine does not force the heavier one on applications
that do not need it.

### 2. Subject provenance is ADR-013 §5, reused verbatim

`AuthContext.resolveEffectiveSubject(requested)` already implements the rule and is used
unchanged: absent or equal to the caller's `sub` → self; different from the caller → the
**delegation marker** is required or the request is rejected with 403.

No new marker, no new configuration, no per-endpoint exception. A backend enumerating on
behalf of its user needs the same marker it already needs to `/evaluate` on behalf of that
user. The two calls a PEP makes for one user now have one authorization story.

### 3. On this transport, attributes come from the body only — derivation does not run

When an explicit subject is supplied, `SubjectAttributeDeriver` is **not** consulted.

This is a correctness rule, not a preference. On this path the validated token belongs to
the *calling backend's service account*, not to the subject being enumerated. Deriving
attributes from the caller's token and attributing them to a different subject is precisely
the attribute forgery ADR-010 exists to prevent — it would let a service account's own
claims decide another person's menu.

There is therefore **no merge and no precedence rule** to reason about: one transport reads
claims, the other reads the body. An absent or empty bag is not an error; it means the
subject has no asserted attributes, which resolves fewer conditions and yields *more*
`conditional` pairs. ADR-029's "degrades, never denies wrongly" property is preserved by
construction, and ADR-011's deny-overrides floor is untouched.

### 4. The cache key and the ETag include the effective attributes — a defect fix

`PermissionsCache` keys entries on `(app, subject)`, and the ETag's `CanonicalForm` hashes
`(app, subject, entries)`. Neither includes the attributes the result was computed from.

Under pushed attributes that is wrong twice over: the same person under two contracts is
the same `(app, subject)`, so the cache would serve **contract A's menu to contract B**,
and — worse, because it survives the TTL — the two results would carry the **same ETag**,
so a client revalidating with `If-None-Match` gets `304 Not Modified` and keeps the wrong
menu.

Both the key and the canonical form gain a **digest of the effective subject attributes**:
the SHA-256 of their canonical JSON, computed with the same technique and for the same
reason as the existing ETag (attribute names and values are unvalidated free strings, so a
delimiter-joined key could be forged to collide).

This applies to **both transports**. On the `GET` the digest is taken over the derived map.
The defect is latent there today — two tokens for one subject can carry different mapped
claims — and fixing one path and not the other would leave the same bug with a narrower
door.

Cardinality grows from one entry per `(app, subject)` to one per distinct attribute set —
in practice, per contract. `MAX_ENTRIES` and the wholesale-clear behaviour already bound
memory and are not changed.

## Reasons

- **The engine already supports it.** The evaluator takes the attribute map as a parameter;
  only the web layer hardcodes its origin. The smallest change that closes the gap is a
  transport, not a redesign.
- **One attribute channel across both surfaces.** After this, `/evaluate` and enumeration
  read attributes the same way, from the same asserter, under the same subject rule. The
  divergence documented in the `GET`'s own OpenAPI description — that a `conditional: false`
  pair can still be denied at enforcement because the two paths resolve attributes
  differently — narrows to the case where a deployment deliberately mixes transports.
- **The engine learns nothing about contracts.** `contractId` is an opaque attribute in a
  bag. No tenancy dimension, no schema, no coupling to the consuming institution's model —
  the same discipline ADR-005 and ADR-010 hold elsewhere.
- **It is additive.** No existing caller changes. The `GET` keeps working for deployments
  whose attributes are in the token.

## Alternatives considered

- **Configure an attribute source (PIP) per ADR-029 and let the engine resolve.** Rejected
  on two counts. The source would receive `{sub}` and nothing else, but the active contract
  is a per-session choice, not a subject fact — the engine cannot ask the right question,
  and passing the contract in would be a caller-supplied attribute reaching the surface that
  forbids them. It would also put a synchronous outbound call to the projection service on
  the hot path of every menu render, making the engine's availability depend on it.
- **Put the role back in the token** (protocol mapper, token exchange, IdP attributes).
  Rejected upstream, not here: the consuming institution's ADR-018 evaluated token, SPI,
  group and IdP-attribute carriers and chose an application-level authorization context
  deliberately. Re-litigating it from inside the PDP would also re-couple the engine to one
  identity provider's extension model, against ADR-003.
- **Query parameters on the existing `GET`.** Rejected: ADR-030 forbids caller-supplied
  attributes on that surface by decision, an attribute bag is not a query-string shape, and
  the values would land in every access log and proxy cache along the way.
- **Let each application's backend compute its own menu.** Rejected: it re-implements policy
  evaluation in every consumer, which is the duplication a dedicated PDP exists to remove,
  and the copies would drift from the policies that actually enforce.
- **Expose the engine to browsers so the frontend calls it directly.** Rejected: `/evaluate`
  accepts asserted attributes from its caller, and it shares reachability with
  `/permissions`. The deployment's access control is network-level; browser reachability
  removes it.
- **Replace the `GET` rather than add to it.** Rejected: it is correct, cheaper and in use
  for deployments whose attributes are in the token. Deleting a working surface to avoid
  having two is a cost with no beneficiary.

### Better option not omitted (and its impact)

- **Accept a `context` bag on enumeration, mirroring `/evaluate`.** Environment attributes
  (`emergency`, time-of-day, channel) condition type-level decisions too. Without them, a
  policy guarded on `context.*` enumerates as `conditional` permanently, even when the
  context is known at render time — the client is told "it depends" about something it could
  have answered. **Impact:** `EnumerationEvaluator.enumerate` gains a parameter, the
  three-valued condition evaluator must resolve context operands the same way it resolves
  subject ones, and the cache digest must cover the context bag as well. Deferred because no
  policy in the catalogue reads `context` yet and adding an unexercised evaluation path is
  how subtle three-valued bugs enter. **Trigger:** the first policy whose condition reads
  `context`, or the first consumer that reports a permanently-conditional pair it can
  resolve itself.
- **Push invalidation instead of the 30-second TTL.** ADR-030 defers it and this ADR does
  not change that, but the attribute digest makes it more attractive: with a
  content-addressed key, an invalidation feed can target exactly the affected entries.
  **Impact:** a change feed from the policy, catalogue and configuration write paths, and the
  cache stops being a single in-process bean. **Trigger:** the staleness window becoming
  visible to administrators, or a distributed deployment.

## Consequences

- New resource method on `PermissionsResource` and a request record for the body; a body
  carrying an `app` field is rejected with 400, as ADR-026 requires everywhere.
- `PermissionsCache.get` and `etag` take the effective attribute map (or its digest); the
  key function and `CanonicalForm` change shape. Existing cached entries are in-process only,
  so there is no migration.
- `SubjectAttributeDeriver` is untouched, and is simply not called on the new path.
- The `GET`'s OpenAPI description is amended: the caveat about the two paths resolving
  attributes differently now names both transports and says which one a PEP asserting
  attributes should use.
- Tests must cover: delegated enumeration without the marker → 403; the same subject with two
  different attribute bags → different bodies **and different ETags**, with no `304` between
  them; an empty bag → more `conditional` pairs, never fewer; and that the caller's own token
  claims never appear in a delegated result.
- ERRORS.md gains no new code: 400 for a malformed body and 403 for a missing delegation
  marker are existing entries.

## Criteria to revisit

- A deployment needs enumeration for a subject whose attributes are partly in the token and
  partly pushed → the merge question this ADR deliberately avoids becomes real, and its
  precedence rule needs its own decision.
- The first policy conditioned on `context` → adopt the deferred `context` bag above.
- Attribute-set cardinality per subject grows beyond a handful (many concurrent contexts,
  not contracts) → revisit the cache's bound and its wholesale-clear behaviour.
- A distributed deployment, or administrators reporting the staleness window → push
  invalidation.

