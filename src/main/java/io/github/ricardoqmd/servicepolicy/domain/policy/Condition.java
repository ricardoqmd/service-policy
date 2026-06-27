package io.github.ricardoqmd.servicepolicy.domain.policy;

/**
 * A boolean condition in a policy rule: either a leaf {@link Comparison} or a composite
 * {@link And}/{@link Or}. Sealed so the evaluator's switch stays exhaustive — adding a
 * new condition type forces every switch to handle it at compile time.
 */
public sealed interface Condition permits Comparison, And, Or {}
