package io.github.ricardoqmd.servicepolicy.domain.policy;

/**
 * Reference to a request attribute by dotted path.
 *
 * <p>Resolved paths: {@code subject.id}, {@code subject.attr.<key>}, {@code resource.type},
 * {@code resource.id}, {@code resource.attr.<key>}, {@code context.<key>}. The engine
 * hardcodes no domain attribute names — anything under {@code .attr.} or {@code context.}
 * is read from the open bags.
 *
 * @param path dotted attribute path.
 */
public record AttributeRef(String path) implements Operand {}
