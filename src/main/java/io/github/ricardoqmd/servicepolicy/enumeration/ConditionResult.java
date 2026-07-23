package io.github.ricardoqmd.servicepolicy.enumeration;

import java.util.Set;

/**
 * The outcome of evaluating one condition three-valued (ADR-030 §4): a {@link TriState} and, when
 * that value is {@code INDETERMINATE}, the {@code resource.attr} names whose absence left it
 * undetermined.
 *
 * <p>{@code contributingResourceAttrs} is the raw material of {@code dependsOn} (ADR-030 §3.1). It
 * carries only names that <em>actually caused</em> the indeterminacy of a branch that is still live:
 * a settled branch — an {@code AND} with a {@code FALSE} child, an {@code OR} with a {@code TRUE}
 * child — contributes nothing, so a client is never told to supply an attribute that would not
 * change the answer. Indeterminacy from a subject attribute, {@code resource.id} or {@code context}
 * marks the result INDETERMINATE but adds no name, because the client neither supplies nor learns
 * those here.
 *
 * <p>The set is empty whenever the value is {@code TRUE} or {@code FALSE}.
 */
public record ConditionResult(TriState value, Set<String> contributingResourceAttrs) {

    public ConditionResult {
        contributingResourceAttrs = Set.copyOf(contributingResourceAttrs);
    }

    static ConditionResult of(boolean holds) {
        return new ConditionResult(holds ? TriState.TRUE : TriState.FALSE, Set.of());
    }

    static ConditionResult settled(TriState value) {
        return new ConditionResult(value, Set.of());
    }

    static ConditionResult indeterminate(Set<String> refs) {
        return new ConditionResult(TriState.INDETERMINATE, refs);
    }

    boolean isIndeterminate() {
        return value == TriState.INDETERMINATE;
    }
}
