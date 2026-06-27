package io.github.ricardoqmd.servicepolicy.domain.policy;

/**
 * Leaf condition: compares two operands with an {@link Operator}.
 *
 * @param op    the comparison operator.
 * @param left  left operand.
 * @param right right operand.
 */
public record Comparison(Operator op, Operand left, Operand right) implements Condition {}
