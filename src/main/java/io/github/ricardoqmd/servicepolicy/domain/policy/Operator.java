package io.github.ricardoqmd.servicepolicy.domain.policy;

/**
 * Comparison operators supported by the condition evaluator.
 *
 * <p>{@code EQ}/{@code NEQ} work on any value; {@code IN}/{@code NOT_IN} expect a
 * collection on the right; ordering operators ({@code GT}/{@code GTE}/{@code LT}/{@code
 * LTE}) require numeric operands.
 */
public enum Operator {
    EQ,
    NEQ,
    IN,
    NOT_IN,
    GT,
    GTE,
    LT,
    LTE;

    public boolean isOrdering() {
        return this == GT || this == GTE || this == LT || this == LTE;
    }
}
