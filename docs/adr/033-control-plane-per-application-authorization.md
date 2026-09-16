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

**The merged, cross-application listing keeps working, scoped.** One read surface has no
application in its route by design: the merged catalogue exists because an operator who
supervises several applications needs a single, server-paginated view that N nested calls
cannot give. It is **scoped to the applications the caller may read, determined before the
query is issued** — not filtered after paging, which would make the reported total and the
page boundaries untrue. The total counts what is visible, and that is the correct total: a
merged view over applications the caller cannot see never meant anything. A caller whose scope
is empty receives an empty page and `200`, never `403`, because a `403` would itself disclose
that other applications exist. This needs no new concept: the scope is the same
`subject.attr.apps` §1 already uses.

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

**Whose token, stated explicitly.** The subject of a control-plane decision is **the caller
itself** — the administrative surface that presented the token. The gate answers *"may this
caller administer this application?"*, not *"may the person operating this caller administer
this application?"* Where a console fronts a person and presents its own credential, this
engine authorizes the console; any finer distinction among that console's operators is that
console's own concern and is invisible here.

The boundary is deliberate, and it is what makes the clause implementable: **this engine can
verify only what a signature attests.** It still buys the property §1 exists for — an
additional administrative surface is confined to the applications its own credential carries,
so adding one no longer makes it an administrator of every application's policies. What it
does not buy is a per-operator gate inside a single surface; that remains where it already is.

A deployment that wants the **person** to be the subject of the gate makes the person's token
the one this engine validates (§4, last paragraph). No clause here changes; only whose
signature is presented.

### 4. On a write, the declared subject attributes; it does not authorize

A write may declare the person on whose behalf it is performed. **That declaration confers no
authority and is not gated:** any caller already authorized to perform the write may make it,
and refusing it would change nothing about what the caller could do. The asymmetry with reads
is the point. On a read, delegation grants a caller access to information about a **different**
subject, so ADR-032 gates it behind a marker. On a write, the caller is authorized as itself
under §1 either way; the declaration changes only **what the record says**.

The audit metadata of ADR-014 therefore records three things, and always all three:

1. the **declared subject** — who the caller says acted;
2. the **calling credential** — the `sub` of the validated token, which the engine knows;
3. the **provenance of (1)** — whether the acting identity was **verified** by this engine or
   merely **declared** by the caller.

Field (3) exists because **one token attests one identity**. This engine validates the signature
it is given and sees exactly the subject that signature names; anything a caller says about a
human standing behind it is an assertion, not an attestation. An audit that cannot distinguish
the two is an audit that will eventually be believed about the wrong person. **Recording the
distinction is what keeps the record honest**, and it is cheaper than pretending it does not
exist.

Note what this is *not*: it is not a consequence of the engine being agnostic. Being agnostic
means this engine holds no organizational model — it does not mean it cannot establish who is
calling, since validating a token is precisely that. Which identity reaches this engine is a
property of what the caller presents, and therefore a deployment choice, not a limit of the
design.

**What a declared identity is worth, stated plainly.** A declared entry says *"this credential
asserted that this person acted"*. That is enough to reconstruct what happened and to hold the
asserting system accountable for its assertion. It is **not** strong non-repudiation: were the
named person to deny the action, the only witness is the system that named them. A deployment
that needs the stronger property makes the identity verified, by the means below.

This is also why the declaration is not gated. A caller holding the credential can already
exercise every authority that credential carries; permitting it to *name* an actor adds record,
not power.

A deployment that wants delegation to be *verified* rather than declared already has the means:
have the intermediary present a token the identity provider issued for the person, or an
exchanged token that names the acting party (RFC 8693). Then this engine validates a signature
instead of trusting a field, (3) says so, and the subject of §3's gate becomes the person. No
clause above changes; only whose signature this engine validates.

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

**Where the control-plane policy set lives.** Policies are per application (ADR-026), and there
is no global set; so the set that governs the control plane lives in **one reserved
application**, whose identifier is configuration with a default. The application being decided
about does not come from where the policy is stored — it travels as `resource.attr.app`, taken
from the route. Separating *where the rule is kept* from *which application it decides about* is
what prevents the bootstrap paradox: were the set kept per application, a newly created
application would be born with no policy authorizing its administration, and nothing could ever
administer it.

The reserved identifier **cannot be created as an ordinary application**: the configuration
endpoint refuses it. Reserving a name in a space that is otherwise free is a cost, and it is the
smaller one: the alternative is a naming grammar this service does not have today and that would
constrain identifiers already in use.

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
- A deployment requires **strong non-repudiation** of control-plane writes, or requires the gate
  of §3 to distinguish operators *within* one administrative surface. Either one is answered the
  same way — the person's token becomes the one this engine validates — and neither requires a
  different design, only a different credential at the last hop.

