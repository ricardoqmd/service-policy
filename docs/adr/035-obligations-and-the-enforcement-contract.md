# 35. Obligations, their vocabulary, and what a policy enforcement point owes them

- **Status:** Accepted
- **Date:** 2026-09-17
- **Deciders:** Ricardo
- **Completes:** ADR-008 (which deferred obligations to "the next increment")
- **Depends on:** ADR-028 (per-application action catalogue), ADR-029 (per-application configuration), ADR-012 and ADR-014 (authoring contract)

## Context

The decision this engine returns already carries a list of obligations. The type exists,
the field is documented, and it is **always empty**: the evaluator returns `List.of()`
and the policy domain has nowhere to declare one.

An obligation is not a description of a decision. It is a **condition attached to it**:
*permit, and mask these fields*; *permit, and record this as an emergency access*. A
policy enforcement point that ignores one has not enforced the decision — it has granted
the access without the condition that made it acceptable. In the masking case that is a
disclosure; in the emergency case it is an access that no one recorded.

Three facts make this worth settling **before** the first obligation exists rather than
with it.

- **The field is already on the wire.** A client written today sees `obligations` and has
  no rule telling it that the list is binding, so the reasonable thing to write is code
  that reads `allowed` and ignores the rest. That code is correct today and becomes a
  fail-open path on the day the engine emits its first obligation.
- **The cheap moment is now and it is closing.** With no obligation in existence, adopting
  a refusal rule costs nothing: there is nothing it can break. Adopting the same rule after
  obligations ship is a behavioural change for every enforcement point already deployed.
- **"An obligation it cannot fulfil" is undecidable without a vocabulary.** A client cannot
  distinguish *"I do not know this identifier"* from *"I know it and it does not apply"*
  unless the set of identifiers is closed somewhere. An open string field makes the refusal
  rule unimplementable, and it makes a typo in a policy indistinguishable from a real
  obligation.

## Decision

### 1. An obligation is a condition on the decision, not information about it

A decision carrying obligations is enforced only if all of them are fulfilled. This is the
difference between an obligation and the `reason` field beside it: ignoring a reason costs
an explanation, ignoring an obligation changes what was granted.

### 2. The vocabulary is declared **per application**, and this engine never learns what it means

An obligation identifier is meaningful only inside the application that fulfils it:
`mask` hides different fields in different systems, and a notification goes somewhere
different in each. Teaching this engine what any identifier *means* would inject exactly
the organizational model it refuses to hold.

So each application declares, in its own configuration (ADR-029), the set of obligation
identifiers its enforcement points can fulfil:

```
obligationTypes: ["mask", "emergency-access-record"]
```

The engine validates the identifier against that set and **passes the attributes through
untouched**. It does not inspect them, does not know their schema, and does not know what
fulfilling one involves.

This is the same shape as the action catalogue of ADR-028, on a different axis: a closed
per-application vocabulary, declared by the people who implement it, that makes an
otherwise free string checkable.

### 3. The vocabulary is checked when the policy is **written**, never when it is evaluated

A policy that declares an obligation outside its application's set is refused at authoring
time, with the ordinary `400` of the authoring contract.

This is the clause that makes the whole design safe, and it is worth stating why. Checking
at evaluation time would mean a typo — `mmask` for `mask` — ships as a valid policy and
then turns, correctly, into a refusal on **every decision that policy governs**, for every
caller, at once. A misspelling would become an authorization outage. Checking at authoring
time turns the same typo into a `400` on the screen of the person who wrote it, before the
policy exists.

The cost is the ordinary one of a closed vocabulary: adding an identifier is a
configuration change that has to happen before the policy that uses it. That ordering is
the point.

### 4. A policy enforcement point that cannot fulfil an obligation refuses the action

On receiving a decision that permits **and** carries an obligation the enforcement point
does not implement, it must not enforce that decision as a permit. It refuses.

This holds even though clause 3 makes the situation rare, because the two guards catch
different failures. The authoring check catches the mistake **in the policy**; this rule
catches the **deployment that has not caught up** — an application that declared an
identifier its running enforcement points do not yet implement. Configuration states what
an application intends to support; only the running process knows what it actually does.

The consequence is deliberate and is the safe direction: an enforcement point that falls
behind its application's configuration denies access it would otherwise have granted. The
alternative is granting access whose condition nobody applied.

### 5. Until an application declares a vocabulary, this engine emits none

The empty list is the contract, not an accident of an unfinished feature. An engine that
emitted an obligation no enforcement point had agreed to fulfil would be triggering clause
4 against its own consumers.

## Reasons

- **A condition that can be dropped in silence is not a condition.** Obligations exist
  precisely for the cases where a bare permit is not acceptable; a contract that lets them
  be ignored gives back exactly what they were introduced to take away.
- **The vocabulary belongs to whoever implements it.** Per-application declaration keeps
  the engine agnostic while still making the identifier checkable, which a global list
  could not do without the engine learning domain nouns.
- **Failing at authoring is failing where it is cheap.** The same mistake costs one `400`
  to its author, or a service-wide denial to everyone.
- **The rule is free today and expensive later.** Nothing can break a contract about a
  thing that does not yet exist.

## Alternatives considered

- **A single closed vocabulary held by the engine.** *This was the first proposal and it
  was wrong.* It makes the identifier checkable at the cost of the engine holding a list
  of domain concepts, contradicting the stance every other clause in this repository
  takes. Per-application declaration buys the same property without it.
- **Free-text identifiers, with no vocabulary.** Rejected: it makes clause 4
  unimplementable — an enforcement point cannot tell an unknown identifier from a typo —
  and turns a misspelling into a silent, total denial. A vocabulary that is not closed is
  free text within a few months whatever the documentation says.
- **Enforcement points ignore obligations they do not recognise.** Rejected: it is the
  fail-open default this ADR exists to prevent. It also makes obligations unusable for the
  cases that motivate them, since the author of a policy could never rely on one.
- **Validate the vocabulary at evaluation time instead of at authoring time.** Rejected on
  blast radius: the error arrives once per decision, in production, for everyone, rather
  than once, at the keyboard of the person who made it.
- **Ship obligations first and settle the enforcement rule with them.** Rejected: that is
  the ordering that makes the rule a breaking change instead of a free one, and it leaves a
  window in which clients are written against a field with no stated meaning.

## Consequences

- Consumers of this engine may implement the refusal rule now, against a stated contract,
  rather than choosing unilaterally what an unfulfillable obligation means.
- An application must declare an obligation identifier before a policy may use it. The
  ordering is enforced by the authoring check, not by convention.
- Obligation attributes are opaque to this engine. Their schema is a contract between the
  policy author and the enforcement points of the same application, and this engine cannot
  validate it — a mistake in the attributes is caught by neither guard here.
- An application that declares an identifier before its enforcement points implement it
  will see denials until they catch up. That is clause 4 working, and it should be read as
  a deployment-ordering requirement rather than as a defect.
- The evaluator still returns an empty list. Nothing in this ADR changes the behaviour of
  the engine today; it fixes the contract that the behaviour will have to satisfy.

## Criteria to revisit

- An obligation is needed that no single application owns — one that a platform-wide
  concern imposes on every application. The per-application vocabulary has no place to
  express it, and a shared namespace would need its own decision about who may declare in
  it.
- Enforcement points need to negotiate what they support at runtime rather than declaring
  it in configuration, for instance because one application runs several enforcement
  points at different versions. That is a capability-reporting mechanism this ADR does not
  provide.
- Obligation attributes prove to need validation. Doing it here would require the engine to
  learn their schemas, so the alternative to weigh first is a per-application schema
  declared alongside the identifier.
