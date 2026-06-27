package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.List;

import io.github.ricardoqmd.servicepolicy.domain.model.AuthorizationRequest;

/**
 * Selects the policies applicable to a request (decision A: match by {@code resource.type}
 * and the verb parsed from the {@code type:verb} action).
 */
public final class PolicySelector {

    /**
     * @param policies all candidate policies (e.g. the active set).
     * @param request  the authorization request.
     * @return the subset that governs the request's resource type and verb.
     */
    public List<Policy> select(List<Policy> policies, AuthorizationRequest request) {
        String verb = verbOf(request.action());
        String resourceType = request.resource().type();
        return policies.stream().filter(p -> p.appliesTo(resourceType, verb)).toList();
    }

    /** Extracts the verb from an action in {@code type:verb} format. */
    static String verbOf(String action) {
        int colon = action.indexOf(':');
        return colon >= 0 ? action.substring(colon + 1) : action;
    }
}
