package io.github.ricardoqmd.servicepolicy.enumeration;

import java.util.List;

/**
 * One {@code (resourceType, action)} pair the caller can act on, as computed by enumeration
 * (ADR-030 §3). Only <em>included</em> pairs are ever built — a deterministic deny, or a pair with no
 * applicable policy, is omitted and never becomes an {@code EnumeratedPair}.
 *
 * <p>This is an enumeration-package type, not the REST view: the boundary receives it and maps it to
 * its own response record, so {@code INDETERMINATE} and the three-valued machinery stay on this side
 * (ADR-030 §4, structural isolation).
 *
 * @param conditional {@code false} when the permit is deterministic (no instance can change it),
 *                    {@code true} when it hangs on the resource instance.
 * @param dependsOn   the resource attribute names the client must supply to resolve a conditional
 *                    pair at {@code /evaluate} (ADR-030 §3.1), sorted for a canonical order; empty
 *                    when {@code conditional} is {@code false}.
 */
public record EnumeratedPair(String resourceType, String action, boolean conditional, List<String> dependsOn) {

    public EnumeratedPair {
        dependsOn = List.copyOf(dependsOn);
    }
}
