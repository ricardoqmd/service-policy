package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.List;

/** Composite condition: holds when every child condition holds. */
public record And(List<Condition> conditions) implements Condition {

    public And {
        conditions = List.copyOf(conditions);
    }
}
