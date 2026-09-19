# 36. An action whose prefix disagrees with the resource type is refused

- **Status:** Accepted
- **Date:** 2026-09-19
- **Deciders:** Ricardo
- **Refines:** ADR-012 (policy authoring contract), ADR-018 (error response contract), ADR-028 (action catalogue)

## Context

An authorization request names an action in `type:verb` form and a resource with its own
`type`. The engine selects policies by the **verb** alone: it takes what follows the first
colon and ignores what precedes it.

So `documento:aprobar` asked about a resource of type `movimiento` is evaluated as
`movimiento:aprobar`. The engine answers a question nobody asked, with a decision that looks
legitimate: a `PERMIT` that the caller will enforce, derived from a policy that governs a
different resource type than the one the caller named.

Nothing reports it. The caller cannot notice, because the response carries the decision and
its reason, not the question as the engine understood it. The mistake is the ordinary one —
a copied line, a constant reused for a neighbouring screen — and its effect is an
authorization answer about the wrong thing.

The prefix exists in the contract precisely to say what the verb is about. Reading it as
decoration is what makes the disagreement invisible.

## Decision

**A request whose action carries a prefix that is not the resource's type is refused with
`400`**, before evaluation, with an error code of its own so the caller can tell this apart
from every other malformed request.

1. The action is split at its **first** colon. What precedes it is the prefix; what follows
   is the verb, and the verb must not be blank.
2. **No prefix, no disagreement:** an action with no colon is the verb, and stays valid. It
   names no type, so it cannot contradict one.
3. A prefix that differs from `resource.type`, by any character, is refused. There is no
   normalization, no case folding and no aliasing: two spellings that a human reads as the
   same type are two types here, exactly as everywhere else in this service.
4. The refusal names both values it compared. It discloses nothing: the caller sent both.
5. In a batch, one disagreeing element refuses the **whole** batch, as every other
   validation failure does (ADR-031), naming the index.

This is a **correction, not a new feature**: the engine stops answering a question other than
the one it was asked. It changes an observable outcome, from a decision to a refusal, and
callers that relied on the prefix being ignored will see `400` where they saw `200`.

## Reasons

- **A wrong answer is worse than no answer.** The previous behaviour produced an
  authorization decision about a resource type the caller did not name, and a PEP enforcing
  it enforces the wrong rule.
- **The check is free and exact.** Both values are already in the request; no store is read
  and no policy is loaded.
- **The alternative pushes the fix to every client.** A client library can validate its own
  callers, and one does; that leaves every other caller of a public engine unprotected, and
  the same property implemented once per client is the class of defect ADR-033 removed one
  layer up.
- **Silence was the defect, not strictness.** A caller that meant the neighbouring type gets
  told; a caller that meant the verb alone is unaffected by §2.

## Alternatives considered

- **Ignore the prefix, as today, and document it.** Rejected: the documentation cannot reach
  the line that was copied, and the failure mode is a decision, not an error.
- **Use the prefix instead of the resource type when they disagree.** Rejected: it picks one
  of two contradictory statements without knowing which the caller meant, and the one it
  discards is the one the rest of the request is about.
- **Require the qualified form everywhere.** Rejected: it breaks callers that send a bare
  verb, which is unambiguous, and buys nothing this decision does not already buy.

## Consequences

- A request whose action disagrees with the resource type is refused, and the caller learns
  which two values disagreed.
- Policies and the action catalogue are untouched: they hold verbs, and their validation
  (ADR-028) does not change.
- Enumeration is unaffected: it names pairs, never an action in prefixed form.
- A caller that was silently evaluating the wrong type will now fail loudly, which is the
  point, and which is a visible change in behaviour for anyone who depended on it.

## Criteria to revisit

- Resource types acquire structure (namespaces, versions) and equality stops being literal.
- A caller appears with a legitimate reason to ask about one type with another type's verb,
  which would mean the verb vocabulary is not per type after all (ADR-028).

