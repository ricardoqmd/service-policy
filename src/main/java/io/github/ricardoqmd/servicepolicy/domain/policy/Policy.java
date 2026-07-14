package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.List;

/**
 * An immutable, versioned authorization policy.
 *
 * <p>Selected for a request when {@code resourceType} matches the request's resource type and the
 * request verb is in {@code actions} (or {@code actions} contains {@code "*"}). Rules are combined
 * with {@code combiningAlgorithm}; if no rule applies, {@code defaultEffect} is used.
 *
 * <p>The owning application is <em>not</em> part of the policy content (ADR-026). A policy's
 * identity is {@code (app, policyId)}, but {@code app} is a coordinate of the head: the server takes
 * it from the request path and stores it there, and it never travels inside the policy document.
 * App scoping is therefore enforced where the app is actually known — see
 * {@code PolicyLifecycleStore#activePoliciesFor}.
 *
 * @param id                 stable policy identifier; unique within its application.
 * @param version            immutable version; never overwritten (see ADR-010).
 * @param resourceType       resource type this policy governs, e.g. {@code document}.
 * @param actions            verbs this policy governs, e.g. {@code [read]}; {@code [*]} = any.
 * @param combiningAlgorithm how applicable rule effects are resolved.
 * @param defaultEffect      effect when no rule applies.
 * @param rules              ordered rules.
 */
public record Policy(
        String id,
        int version,
        String resourceType,
        List<String> actions,
        CombiningAlgorithm combiningAlgorithm,
        Effect defaultEffect,
        List<Rule> rules) {

    public Policy {
        actions = List.copyOf(actions);
        rules = List.copyOf(rules);
    }

    /** @return {@code true} if this policy governs the given resource type and verb. */
    public boolean appliesTo(String resourceType, String verb) {
        return this.resourceType.equals(resourceType) && (actions.contains("*") || actions.contains(verb));
    }
}
