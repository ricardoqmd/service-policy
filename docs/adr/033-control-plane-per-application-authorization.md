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

**What "an additional policy" can and cannot do today, measured.** A policy that matches no
rule contributes its default effect, and a `DENY` from any policy overrides the rest, so as the
engine stands an additional policy can **narrow** access (default `PERMIT`, one `DENY` rule) but
cannot **widen** it: an added rule that permits one operator leaves the baseline contributing
`DENY` for that request, and — worse — an added policy whose default is `DENY` withdraws the
action from every caller it does not name. Widening therefore means revising the policy that
governs that action, not adding one beside it. This is a limit of the engine's combining, not of
this design: the engine has no "not applicable" outcome for a policy that matched nothing. Giving
it one is decided separately; this clause states what holds until then.

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
`subject.attr.apps` §1 already uses, narrowed to those the read decision permits — so an
operator-authored `DENY` removes an application from the merged view exactly as it removes it
from the per-application read. An application **outside** `subject.attr.apps` is not a candidate
here even if a policy would permit reading it; scope and decision agree in the direction that can
only show less.

### 3. Subject attributes for control-plane decisions come from the validated token only

Control-plane decisions resolve `subject.attr.*` from the validated token, through a claim
mapping of the same form as ADR-029's, and **never** from the request body. The mapping applied
is the one stored in the configuration of the **reserved control-plane application** (§6), not
the configuration of the application being decided about: that application may not have a
configuration yet — creating one is itself a control-plane write — and the merged catalogue of
§2 names no application at all. The
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

Documents written by installation itself are not a caller's write. They record the installation
identity in all three fields, with provenance **verified**, so no consumer has to treat an absence
as a special case.

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

**The reserved identifier is part of the installation, not of the running configuration.** It is
recorded in the installation marker when the marker is written, and from then on a deployment whose
configured identifier differs from the recorded one **does not start**. Without that binding, a
single configuration value changed after installation moves the control plane onto whichever policies
happen to live in another application — an application an ordinary tenant administrator may author
freely — which is the unconditional access §5 removes, reachable by a deployment mistake.

**And the binding holds while the service runs, not only when it starts.** A decision reads the
marker already, to know whether installation mode is over; it compares the recorded identifier with
the configured one in the same breath and **denies every control-plane call** when they disagree.
A startup check alone leaves the window in which installation is still open: a second deployment,
started earlier with a different identifier, keeps serving from another application's policies after
someone else's write closed installation, and nothing tells it.

**Installation seeds into an empty reserved application, or it does not seed.** Seeding leaves
whatever it finds untouched, which is what makes it idempotent — and that is also how an existing
document becomes the rule. A service with no installation marker whose reserved application already
holds a document installation did not write **refuses to start**, naming the application. The
alternative is that whoever could write that application before it was reserved decides, silently,
who administers everything afterwards. Installation's own documents are recognisable because it
records itself as their subject (§4); nothing here inspects what such a document *says*, because a
document that merely resembles the baseline is still not one this installation wrote.

**Seeding finishes what it started (ADR-019).** Installation writes documents in more than one collection,
so it has the same failure window every write in this service has, and the same answer: ADR-019 fixes a
commit point and makes a retry **complete** a partially applied operation rather than skip it. Startup
therefore converges on the end state — configuration, catalogue entry, and a baseline with an **active**
version — completing its own half-written work, never merely checking that a document exists. Completing is
not adopting: every document in the reserved application at that moment carries installation's own marks,
because the refusal above already ran. And because a convergence that silently fails is worse than a refusal,
startup verifies the end state afterwards and **refuses** if it does not hold.

**Recovery is the operator's, and the refusal is what makes it possible.** The refusal names the
application and what is in the way, document by document, so the operator can act on it with the API
they already have. **What that API can remove decides which recovery applies**, and the refusal says
which: a configuration document and a catalogue entry can be deleted, so emptying that application
before upgrading is a real option; **a policy cannot** — versions are immutable and append-only
(ADR-016) and no endpoint removes them — so where a policy is in the way the recovery is to point the
reserved identifier at an application that does not exist yet. Deactivating such a policy is not
enough: the check does not read what a document says, and a deactivated head is one activation away
from saying it again. There is deliberately **no setting that adopts the existing documents** —
that is the compatibility switch §5 rejects, wearing the clothes of a migration aid — and installation
deletes nothing on its own: a service that quietly removes an operator's documents while starting is a
worse failure than one that refuses to start and says why.

**A service that cannot make a control-plane decision, or cannot vouch for who administers it, does
not start.** The startup conditions are refusals rather than warnings, because each one leaves an
installation nobody can administer or one administered by something nobody chose: no installation
marker and no configured claim path for `apps`; no installation marker and no configured bootstrap
value; no installation marker and a reserved application that already holds foreign documents; a
marker whose recorded identifier differs from the configured one, or that records none; a blank
configured identifier; and a marker with no usable `apps` mapping stored for the reserved
application — where *usable* means a non-empty claim path, since a stored value that cannot resolve
one leaves exactly the state this condition exists to prevent. A warning is the right signal only
where the service still works: a configured claim path that differs from a usable stored mapping,
which is ignored.

The reserved identifier **cannot be created as an ordinary application**: the configuration
endpoint refuses it. Reserving a name in a space that is otherwise free is a cost, and it is the
smaller one: the alternative is a naming grammar this service does not have today and that would
constrain identifiers already in use.

**Where the control-plane claim mapping lives.** The mapping of §3 is **required deployment
configuration until installation, and stored configuration after it**:

- While no installation marker exists, the deployment must supply the claim path that carries
  the caller's applications. **A service that is not installed and lacks that value does not
  start**: seeding an empty mapping would exclude every caller from the first minute.
- Installation seeds, idempotently, the reserved application's configuration with that mapping
  and the baseline control-plane policy set. Seeding does not itself record the installation
  marker; the first successful control-plane write does.
- From then on **the stored configuration is the only source**. The deployment value is
  ignored; if it differs from what is stored, the service reports the divergence at startup and
  applies nothing. Two live sources for the rule that decides who administers would leave no
  arbiter the day they disagree.
- The stored mapping is **updated through the ordinary configuration endpoint**. That update is
  a control-plane write about the reserved application and is authorized like any other, so the
  authority to change who counts as an administrator is itself expressed as policy.
- The reserved application's configuration **cannot be deleted**, just as it cannot be created
  as an ordinary application.
- **An update that would leave its own caller without the reserved application is refused at
  write time**: the service resolves the proposed mapping against the caller's validated token
  and rejects the write if the reserved application is not among the resulting `apps`. This does
  not prevent excluding other callers; it guarantees that the identity making a change can
  always undo it, so a mistaken mapping is repaired through the API rather than by
  reinstallation.

Deployment configuration alone was rejected because the rule of who administers would change
only by redeploying, through a second mapping mechanism that never goes away. Stored
configuration alone was rejected because a fresh store has nothing to seed it from.

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
  perform one successful control-plane write with the bootstrap credential to close installation,
  and deploy the updated console. **Between the deployment and that write, every console is
  refused**: installation mode accepts the bootstrap subject and no one else.
- **Every instance of the previous version stops before this one starts.** A version that predates this
  change honours the global marker and ignores everything decided here, so one left running against the same
  store during a rolling deployment can replace the policy set this version seeded — and the access this ADR
  removes survives the upgrade, in a process this version does not control. The requirement is a deployment
  one and it is stated as such; it is not enforceable from inside this service, which is exactly why it is
  written down.
- The control-plane policy set is **editable like any other policy set**, so an operator can
  revoke their own access. This is a property of the design, not a defect: recovery is
  reinstallation or direct repair of the store, and both are deliberate acts.
- Control-plane decisions now evaluate a policy on every call, including reads. The cost is
  one evaluation against a small, cacheable policy set.
- `audit.createdBy` keeps its meaning — the credential that called — and the acting identity is
  carried by the two fields added beside it. Entries written before this change have neither, and
  their absence is what marks them as older; it is not a claim that the caller and the actor were
  the same.
- Two provenance rules now coexist and must not be confused: caller-asserted for the data
  plane, token-derived for the control plane. §3 exists to keep them apart.

## Criteria to revisit

- An administrative surface appears that cannot present a token carrying its applications —
  for instance an operator acting across tenants without a per-tenant claim.
- The control-plane policy set grows beyond what a single evaluation can serve efficiently on
  every read.
- Delegation on writes proves insufficient to attribute an action, for example where more
  than one hop separates the person from this service.
- A future change again alters **who may write the control plane**, and by then deployments exist that
  cannot be stopped for an upgrade. The answer then is a maintenance mode in the *preceding* version — one
  that freezes control-plane writes while the next version installs — which only helps if it ships before it
  is needed. It is not built now because every version from this one on enforces the same gate, so two of
  them overlapping during a deployment is not a hazard: the hazard is specific to a version that still
  carries the global marker.
- A deployment requires **strong non-repudiation** of control-plane writes, or requires the gate
  of §3 to distinguish operators *within* one administrative surface. Either one is answered the
  same way — the person's token becomes the one this engine validates — and neither requires a
  different design, only a different credential at the last hop.

