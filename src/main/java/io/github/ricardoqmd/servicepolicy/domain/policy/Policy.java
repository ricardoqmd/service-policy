package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.List;

/**
 * An immutable, versioned authorization policy.
 *
 * <p>Selected for a request when {@code app} and {@code resourceType} match the request's app
 * and resource type, and the request verb is in {@code actions} (or {@code actions} contains
 * {@code "*"}). Rules are combined with {@code combiningAlgorithm}; if no rule applies,
 * {@code defaultEffect} is used.
 *
 * @param app                application that owns this policy (ADR-024); required and immutable.
 * @param id                 stable policy identifier.
 * @param version            immutable version; never overwritten (see ADR-010).
 * @param resourceType       resource type this policy governs, e.g. {@code document}.
 * @param actions            verbs this policy governs, e.g. {@code [read]}; {@code [*]} = any.
 * @param combiningAlgorithm how applicable rule effects are resolved.
 * @param defaultEffect      effect when no rule applies.
 * @param rules              ordered rules.
 */
public record Policy(
        String app,
        String id,
        int version,
        String resourceType,
        List<String> actions,
        CombiningAlgorithm combiningAlgorithm,
        Effect defaultEffect,
        List<Rule> rules) {

    public Policy {
        if (app == null || app.isBlank()) {
            throw new IllegalArgumentException("app must not be null or blank");
        }
        actions = List.copyOf(actions);
        rules = List.copyOf(rules);
    }

    /**
     * @return {@code true} if this policy governs the given app, resource type, and verb (ADR-024).
     */
    public boolean appliesTo(String app, String resourceType, String verb) {
        return this.app.equals(app)
                && this.resourceType.equals(resourceType)
                && (actions.contains("*") || actions.contains(verb));
    }
}
