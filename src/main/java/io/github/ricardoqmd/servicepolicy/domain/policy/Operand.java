package io.github.ricardoqmd.servicepolicy.domain.policy;

/** An operand of a {@link Comparison}: either an attribute reference or a literal. */
public sealed interface Operand permits AttributeRef, Literal {}
