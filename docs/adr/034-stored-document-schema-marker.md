# 34. Stored documents carry a schema marker

- **Status:** Accepted
- **Date:** 2026-09-14
- **Deciders:** Ricardo
- **Depends on:** ADR-016 (immutable, append-only policy versions), ADR-026 (composite policy identity), ADR-029 (per-app configuration)

## Context

Persisted documents carry no indication of the shape they were written in. A policy version
holds `app`, `policyId`, `version`, `content` and `audit`; `version` is the **policy's**
version under ADR-016's head-pointer model, not the document's. `content` is a
storage-agnostic sub-document whose internal shape is defined by the mapper of the release
that wrote it, and nothing stored alongside it says which release that was. The same is true
of the configuration documents of ADR-029.

While one shape has ever existed this costs nothing. The first change that alters the shape
of `content` makes it expensive: a reader confronted with a mixed collection must **infer**
each document's shape from its contents, and inference of that kind is where migrations go
wrong quietly. ADR-016's immutability helps — old versions are never rewritten, so a change
could leave them untouched and write new ones in the new shape — but only if a reader can
tell the two apart, which today it cannot.

The marker is additive, changes no external contract, and is cheapest to introduce while
exactly one shape exists.

## Decision

1. Every persisted document written by this service carries an explicit **schema marker**: a
   small integer, distinct from any domain-level version, naming the shape of the document.
2. **A marker describes the content it sits next to, not merely the document it lives in.** For a
   document that is itself immutable (a policy version, ADR-016) that is the same thing: the marker
   is written on creation and never altered. For a document that is mutable by design **and that
   copies content in from elsewhere**, the copy carries its own marker, taken from the source at the
   moment of the copy and rewritten whenever the copy is replaced.

   The case that makes this necessary is the head pointer of ADR-016: activation copies a version's
   content into the head. A single per-document marker would let a head written under shape *N*
   hold content copied from a version written under shape *M* — which is exactly the inference this
   ADR exists to remove, reintroduced inside the document that was supposed to answer it. The head
   therefore carries a marker for its own shape and a second one for the content it holds.

3. Documents already stored without a marker are, by definition, shape 1. Readers treat an
   absent marker as shape 1 and do not rewrite existing documents to add it: a backfill would
   mutate append-only records to record a fact that the absence already conveys.

4. The marker is **internal**. It is not part of any request or response body and does not
   appear in the OpenAPI specification (ADR-015); it is not a second API version.

5. A reader that encounters a marker it does not recognise fails the read explicitly rather
   than guessing. An engine that guesses at the shape of an authorization rule is worse than
   one that stops.

6. **That failure is not a client error and is not given a public error code.** It is raised as an
   unmapped exception and surfaces as the framework's default `500`, which is how this service
   already treats infrastructure failures that the caller cannot act on. The error contract of
   ADR-018 enumerates only statuses a client caused, and extending it for this one case would leave
   every other server-side failure outside the contract while spending a contract change on a
   fraction of the problem. A general shape for server-side failures is a separate decision.

7. Recognition lives at the **persistence boundary**, once per document type, not in the mapping
   layer: not every read of a stored document passes through a mapper, and a guard the reader can
   bypass is not a guard.

8. **A build does not write to a document whose shape it does not recognise.** Reading is not the
   only way to act on a stored document: an update that sets fields by the names and meanings of the
   shape it knows has assumed that shape just as surely as a read would have, and it leaves the
   document worse than it found it. The guard therefore belongs to the write condition itself, not to
   a read performed before it — a check-then-write pair is a race, and a write whose refusal arrives
   after it has been applied is not a refusal.

9. **A marker for copied content exists only while that content exists.** Removing the content removes
   its marker; a document that holds no copied content carries none, and a reader does not check a
   marker for content that is not there. Otherwise a marker outlives what it describes and can make a
   document unreadable over an emptiness. *The promise is bounded twice, and both bounds are declared:
   a stored value that is not a marker at all under §10 fails before anything decides whether there is
   content to describe; and while a copied-content marker is malformed, the document's revision cannot
   be obtained through the API — a read that would return the content refuses, so only a caller that
   already holds the current revision can perform the write that repairs it. Both fail closed.*

10. **The marker is an integer, and only an integer.** Its width is not part of it: the same integer
    stored as a 32-bit or a 64-bit value names the same shape. Any other stored type — a decimal, a
    floating-point value, a string, an array, a boolean, a null — **is not a marker**. It is a
    malformed field, and nothing in this service writes one; only a hand edit or a defect produces
    one. What must never happen is the reverse: a value that is *not* the known marker being read as
    though it were, in particular through a narrowing conversion that discards high bits.

11. **The write condition and the read guard admit the same values**, so that a build cannot write to
    a document it cannot read. Where they cannot be made to coincide exactly, the permitted direction
    is the **read being more tolerant than the write**: the worst outcome is then a malformed
    document that has become read-only, which a person can repair. The forbidden direction is a write
    a build is allowed to perform on a document it cannot read back. *This concerns malformed markers
    only: a malformed marker is not a different shape, so the harm is an inconsistent answer, never a
    document interpreted under the wrong shape. The rule exists so that the inconsistency cannot grow
    into the other kind.*

## Reasons

- **Inference is the failure mode.** The cost of a future shape change is dominated by
  whether a reader can identify what it is holding. One stored integer removes the question.
- **Additive now, structural later.** Introducing the marker once two shapes already coexist
  is itself the migration this decision is meant to make tractable.
- **It does not leak into the contract.** Clients neither send nor see it, so no consumer is
  coupled to storage evolution.

## Alternatives considered

- **Do nothing and infer from content.** Rejected: workable for a shape change with an
  unambiguous discriminator, unworkable in general, and undetectable when it is wrong.
- **Derive the shape from the release that wrote the document** (store a service version).
  Rejected: it couples storage to release numbering and makes every release a potential
  shape, when shapes change far more rarely than releases.
- **Version the collection instead of the document** — migrate a whole collection between
  shapes on deployment. Rejected: it turns every shape change into downtime proportional to
  the store, and it is incompatible with ADR-016's immutable versions.

## Consequences

- One additional field per stored document, unindexed unless a migration needs it.
- Readers gain a branch point they must keep honest; a shape change now has an obvious,
  reviewable place to live.
- The absence of the marker becomes meaningful and must stay meaningful: nothing may write a
  document without one from this change onward.

## Criteria to revisit

- Shapes begin to vary per document rather than per release, which would argue for a
  descriptor rather than an integer.
- A store-level schema mechanism becomes available that offers the same guarantee without an
  application-managed field.

