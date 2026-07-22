package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.List;

/**
 * An immutable, versioned authorization policy.
 *
 * <p>Selected for a request when {@code resourceType} matches the request's resource type and the
 * request verb is in {@code actions}. Rules are combined with {@code combiningAlgorithm}; if no
 * rule applies, {@code defaultEffect} is used.
 *
 * <p>{@code actions} is always a list of literal action ids — there is no wildcard here (ADR-028).
 * {@code ["*"]} is authoring-time input sugar: it is expanded against the application's action
 * catalogue when the version is written, and the explicit list is what gets stored. The evaluator
 * therefore never meets a {@code "*"} and does not special-case one, which is what makes "adding a
 * verb to the catalogue widens no existing policy" true rather than merely intended.
 *
 * <p>A policy whose stored {@code actions} nonetheless contains {@code "*"} is <em>inert</em>: it
 * matches no verb, so it is never selected, and an unselected policy contributes nothing at all —
 * not its rules and not its {@code defaultEffect}. Inert is not the same as denying. Under
 * deny-overrides a stored wildcard DENY no longer suppresses another policy's permit for the same
 * {@code (resourceType, verb)}, because it is not among the candidates being combined. Such a
 * document cannot be produced through the write path (ADR-028 resolves actions at the single write
 * door), so its only origin is data written out of band, which is outside this engine's trust
 * boundary and must be recreated.
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
 * @param actions            literal verbs this policy governs, e.g. {@code [read, update]}; never a
 *                           wildcard (ADR-028 expands it at authoring).
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

    /**
     * @return {@code true} if this policy governs the given resource type and verb. Membership is
     *     literal: {@code "*"} is not a pattern here (ADR-028), so a policy whose stored actions are
     *     {@code ["*"]} governs nothing.
     */
    public boolean appliesTo(String resourceType, String verb) {
        return this.resourceType.equals(resourceType) && actions.contains(verb);
    }
}
