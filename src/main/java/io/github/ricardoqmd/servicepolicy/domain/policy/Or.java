package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.List;

/** Composite condition: holds when at least one child condition holds. */
public record Or(List<Condition> conditions) implements Condition {

    public Or {
        conditions = List.copyOf(conditions);
    }
}
