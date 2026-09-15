# 33. Per-application authorization of the control plane

- **Status:** Accepted
- **Date:** 2026-09-14
- **Deciders:** Ricardo
- **Supersedes in part:** ADR-013 (the global administrative marker is removed)
- **Depends on:** ADR-010 (caller-asserted attributes), ADR-013 (endpoint authorization and subject provenance), ADR-016 (head-pointer activation), ADR-026 (composite policy identity), ADR-029 (per-app configuration), ADR-030 and ADR-032 (enumeration and its delegation marker)

## Context

The control plane is authorized by a single binary marker. ADR-013 gave this service two
markers, each read from a configured claim: one that grants administrative writes and one
that identifies a policy client. The administrative marker is **global** — a caller that
carries it may author, activate or deactivate a policy for **any** application, and may read
every application's policies.

That was sufficient while exactly one administrative surface existed. It stops being
sufficient the moment the engine is operated by more than one console, or by an operator who
administers only some of the applications the engine serves. That is the ordinary condition
of a multi-tenant decision engine, and it is the condition this service already claims to
support: ADR-006 makes the tenant explicit, ADR-026 makes policy identity composite, and
ADR-029 makes configuration per application. Authorization of the administration is the one
axis that stayed global.

The consequence is not theoretical. Handing the marker to a second administrative surface
makes that surface an administrator of every tenant's policies. Today the per-application
gate exists only **outside** the engine, in whichever console fronts it, written in that
console's language, and it would have to be reimplemented in every console that follows. A
security property implemented once per client is a property that will diverge, and nothing
in the system reports the day it does.

Stated as a product property: **a decision engine whose administration is only safe behind
one specific console is not a neutral product — it is half a product plus an unwritten
deployment requirement.**

Three further facts constrain any solution:

- **Subject attributes at `/evaluate` are caller-asserted** (ADR-010 §1). A gate that reads
  `subject.attr.*` out of the request body can be satisfied by the caller asserting it about
  itself. Enumeration, by contrast, derives attributes from the validated token through the
  per-app claim mapping (ADR-029, ADR-030 §1) — two different provenances, by design.
- **Writes record the calling credential.** With a service account standing in front of a
  person, `audit.createdBy` records the machine. Adding an intermediary would lose the
  person twice.
- **Reads already have a delegation marker** (`:enumerate`, ADR-032). Writes have no
  equivalent.

## Decision

### 1. Control-plane authorization is expressed as policy, and evaluated by this engine

The service ships **no organizational model**. Administrative access is decided by
evaluating an ordinary policy set whose `resourceType` is `policy` and whose actions are
`policy:read`, `policy:write`, `policy:activate` and `policy:deactivate`. The baseline rule
is:

```
permit  when  resource.attr.app  IN  subject.attr.apps
```

Anything finer — read-only access to one application, a platform-wide operator, a
time-bounded grant — is **an additional policy**, authored through the same API as every
other policy. The engine does not learn what an "owner" or an "administrator" is; it
evaluates one more policy over a resource type that happens to be called `policy`.

### 2. The gate covers reads and writes

All four actions are enforced. A gate on writes alone would leave every console obliged to
reimplement the read gate, which is the duplication this ADR exists to remove. `403` on a
denied read is a normal outcome (ADR-017, ADR-018) and carries no detail that would let a
caller enumerate applications it cannot see.

### 3. Subject attributes for control-plane decisions come from the validated token only

Control-plane decisions resolve `subject.attr.*` through the per-app claim mapping of
ADR-029 — the path enumeration already uses — and **never** from the request body. The
caller-asserted channel of ADR-010 remains correct for the data plane, where the caller is a
policy enforcement point asserting facts about a third party under its own accountability;
it is **not** admissible where the caller is the subject of the decision, because a caller
that may assert its own attributes may grant itself the access being checked.

> This is the property the whole decision rests on. An implementation that satisfies every
> other clause and reads these attributes from the request has reintroduced the defect under
> a new name.

### 4. Writes carry the acting subject

A write may declare the person on whose behalf it is performed, mirroring the delegation
marker `:enumerate` already accepts for reads (ADR-032). The declaration is honoured only
for callers a policy allows to delegate; it is rejected, not ignored, otherwise. The audit
metadata of ADR-014 records the **declared subject** as the author and the calling
credential alongside it, so both remain answerable.

### 5. The global administrative marker is removed, with no compatibility switch

`authz-admin` ceases to exist. There is no configuration setting that restores the previous
behaviour, and therefore no deployment can be left, by omission or by mistake, in the state
this ADR removes.

**This is a breaking change to the control-plane contract.** It is taken deliberately while
the number of deployed administrative consumers is at its historical minimum; the same
change made later costs a coordinated migration of every console in operation.

### 6. Installation mode, closed by a one-way marker

Before the first control-plane policy exists there is no policy that can authorize writing
one. While the store carries **no** record of installation, the service accepts control-plane
reads and writes from a single **bootstrap subject identified by a configured claim value**
— not a username and password: this service has no user store, and identity comes from a
validated token (ADR-013).

The first successful control-plane write records a **one-way installation marker** and
installation mode ends permanently. The marker is explicit and persisted; **installation
mode is never inferred from the absence of policies**, because deleting every policy would
otherwise reopen the door. Losing the store is a reinstallation, and behaves as one.

The bootstrap claim value may remain configured afterwards. It grants nothing once the
marker exists.

### 7. Reachability

The service remains unreachable from public networks. The set of permitted origins is
"registered administrative surfaces and policy enforcement points" rather than a single
named console. Network reachability is a deployment concern and is not a substitute for any
clause above.

## Reasons

- **A security property belongs where the data is.** The per-application gate guards
  policies; policies live here. Written once next to the data it protects, it cannot
  diverge between clients, and it is covered by this repository's own tests and audits.
- **Dogfooding keeps the engine neutral.** The alternative that "teaches the engine who owns
  what" is the one that injects an organizational model. Expressing the same requirement as
  a policy leaves the engine exactly as generic as before, and demonstrates the product
  against itself.
- **More administrative surfaces argue *for* this change, not against it.** Each additional
  console is another copy of the gate that will not be written; centralizing it is what makes
  additional consoles safe rather than expensive.
- **A permanent escape hatch is not a safety feature.** A switch that restores the previous
  behaviour is configuration a deployment can be left holding, silently, with no signal that
  it is open — the same class of defect this ADR removes, one layer up.
- **The audit answers "who", not "what credential".** Delegation on writes is what makes the
  record name the person, and it mirrors a mechanism reads already have.

## Alternatives considered

- **Keep the global marker and gate per application in each console.** Rejected: it is the
  status quo, it scales by duplication, and it makes the engine's safety depend on software
  the engine does not ship or test.
- **Ship the change behind a compatibility flag, default off.** *This is the alternative with
  the strongest case, and it was rejected on impact rather than on principle.* A flag
  decouples deploying the engine from migrating its writers, which is valuable when those are
  operated by different parties on different schedules. It costs: two evaluation paths that
  must both be tested for as long as the flag exists, one configuration value per environment
  that can be wrong, a window during which a deployment runs with the gate open and nothing
  reports it, and a second release to remove the flag — which, if it never happens, leaves
  exactly the back door this ADR removes. With the population of deployed administrative
  consumers at its minimum, the flag protects against a migration risk that does not yet
  exist, and rollback is already available by redeploying the previous image.
- **Encode actions inside the subject attribute** (`apps` carrying per-action grants).
  Rejected: it turns a flat, mappable claim into a structure the engine must interpret, which
  is precisely the organizational model this service refuses to learn. Finer grants are
  expressed as additional policies instead.
- **A separate, privileged namespace for control-plane rules**, outside the ordinary policy
  API. Rejected: it removes the self-reference (rules about reading rules) at the cost of a
  second administration mechanism, a second audit path and a second thing to secure — and it
  forfeits the property that makes this design defensible, namely that the control plane is
  governed by the same mechanism the product offers everyone else. The self-reference is
  instead terminated by §6, outside evaluation.
- **A bootstrap command run by an operator, outside the service.** Rejected as the primary
  mechanism: a fresh environment stays unusable until someone remembers to run it, and the
  command still needs a way to authenticate before any policy permits writing — so it needs
  §6 regardless. It remains available as an operational tool on top of §6.

## Consequences

- **Breaking.** Callers that relied on the global marker stop working at the version that
  carries this change. The migration is: configure the claim that carries the caller's
  applications, deploy this service, let installation mode seed the control-plane policy set,
  deploy the updated console.
- The control-plane policy set is **editable like any other policy set**, so an operator can
  revoke their own access. This is a property of the design, not a defect: recovery is
  reinstallation or direct repair of the store, and both are deliberate acts.
- Control-plane decisions now evaluate a policy on every call, including reads. The cost is
  one evaluation against a small, cacheable policy set.
- `audit.createdBy` changes meaning going forward. Entries written before this change record
  the calling credential and cannot be reinterpreted; the discontinuity is permanent and
  should be read as such.
- Two provenance rules now coexist and must not be confused: caller-asserted for the data
  plane, token-derived for the control plane. §3 exists to keep them apart.

## Criteria to revisit

- An administrative surface appears that cannot present a token carrying its applications —
  for instance an operator acting across tenants without a per-tenant claim.
- The control-plane policy set grows beyond what a single evaluation can serve efficiently on
  every read.
- Delegation on writes proves insufficient to attribute an action, for example where more
  than one hop separates the person from this service.

