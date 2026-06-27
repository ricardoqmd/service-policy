package io.github.ricardoqmd.servicepolicy.domain.policy;

/**
 * A constant value operand, e.g. {@code true}, {@code "reviewer"}, {@code 42}.
 *
 * @param value the literal value; may be null.
 */
public record Literal(Object value) implements Operand {}
