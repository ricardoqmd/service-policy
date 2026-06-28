# 10. Subject attribute provenance: caller-asserted, behind a port

Date: 2026-06-28

## Status

Accepted

## Context

Rules evaluate over a subject attribute bag (`subject.attr.*`); for the MVP domain
(ADR 8) the `area-scope` rule compares `resource.attr.area == subject.attr.area`. The
question left open until now was where the subject's non-identity attributes (such as
`area`) come from.

Three sources were on the table: (A) a collection of attributes preloaded in the PDP, (B)
an external Policy Information Point queried at evaluation time, (C) a mix. A fourth, simpler
option emerged: the **caller (the PEP) asserts the subject's attributes in the request**, the
same way it already asserts the resource's attributes.

Identity is a separate concern: the subject `id` is taken from the validated JWT (ADR 6),
never from the request body — a caller may assert attributes *about* an identity, but not
*who* the subject is.

## Decision

1. **Non-JWT subject attributes are provided by the caller in the evaluation request.** The
   PDP does not fetch them from source systems and does not store them.
2. **Identity comes from the validated JWT only** (`sub`, fallback `preferred_username`).
   A request with no usable subject identity is rejected as an invalid token.
3. **Attribute resolution sits behind a port** (`SubjectAttributeProvider`). The MVP adapter
   is trivial — it returns the attributes carried in the request. Swapping to external
   enrichment later (option B) is an adapter change, not a domain change.
4. A missing subject attribute is **not an error**: it yields an empty value, the dependent
   rule does not match, and evaluation falls through to `defaultEffect: deny` (less
   privilege).

## Reasons

- **Consistency with the resource side.** Resource attributes already arrive from the caller
  in the request body; sourcing subject attributes the same way removes a special case
  rather than adding one.
- **Stateless and fast (ADR 11).** No outbound call to source systems on the evaluation path
  means no added latency, no extra failure mode, and no coupling to those systems' freshness.
- **Trust boundary is explicit.** Identity is authenticated (spoof-resistant); attributes are
  asserted by the PEP. This is acceptable because PEPs are internal and trusted, and the
  assertion is *about* an already-authenticated identity, not the identity itself.
- **The port keeps the door open cheaply.** Building the seam now (even with a trivial
  adapter) means promoting to external enrichment requires no re-architecture — consistent
  with "build the port now even when the adapter is trivial" and "promote at second
  consumer".

## Alternatives considered

- **(A) Attributes preloaded in the PDP:** rejected — adds a collection and seeding the PDP
  does not own; the caller already has the context.
- **(B) External PIP at evaluation time:** rejected for now — outbound dependency on source
  systems (HR, catalogs), latency, and its own caching/timeout/fallback decisions. Justified
  only by a real second consumer; reachable later via the port.
- **(C) Mixed:** premature; no evidence yet that any attribute must be PDP-sourced.

## Consequences

- (+) Evaluation stays stateless and within the latency budget; no coupling to source
  systems.
- (+) The evaluation request gains an additive `subject.attributes` field; callers that omit
  it get an empty bag (less privilege), so it does not break existing callers — but it is an
  addition to the frozen contract surface (ADR 4) and is recorded as such.
- (+) External enrichment remains a future adapter swap, not a rewrite.
- (−) The PDP trusts caller-asserted attributes; a compromised or buggy PEP could assert
  false attributes. Mitigated by the internal/trusted PEP assumption and authenticated
  identity; revisit if PEPs stop being trusted.

## Revisit if

- A real attribute appears that the PEP cannot assert and the PDP must source itself (then
  implement an enriching `SubjectAttributeProvider` adapter — option B).
- The PEP trust assumption no longer holds (then attributes need PDP-side verification).
