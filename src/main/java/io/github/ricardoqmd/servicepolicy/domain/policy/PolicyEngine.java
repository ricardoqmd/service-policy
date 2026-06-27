package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.List;

import io.github.ricardoqmd.servicepolicy.domain.model.AuthorizationRequest;

/**
 * Evaluates an {@link AuthorizationRequest} against the applicable policies and produces
 * an {@link AuthorizationDecision}.
 *
 * <p>Two deny-overrides (fail-safe) levels:
 *
 * <ol>
 *   <li><b>Within a policy:</b> a matched DENY rule overrides matched PERMIT rules; if no
 *       rule matches, the policy's {@code defaultEffect} applies.
 *   <li><b>Across policies:</b> any policy yielding DENY overrides PERMIT policies; if no
 *       policy applies, the request is denied (fail-safe default).
 * </ol>
 *
 * <p>Pure and stateless. Policy loading and selection are the caller's responsibility (see
 * {@link PolicySelector}).
 *
 * <p>Note: cross-policy combining when multiple applicable policies default without a
 * matching rule is intentionally left to a future decision; the MVP ships a single
 * applicable policy.
 */
public final class PolicyEngine {

    private final ConditionEvaluator conditionEvaluator;

    public PolicyEngine(ConditionEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

    /**
     * @param policies the policies already selected as applicable to the request.
     * @param request  the authorization request.
     * @return the combined decision.
     */
    public AuthorizationDecision evaluate(List<Policy> policies, AuthorizationRequest request) {
        AuthorizationDecision permit = null;
        for (Policy policy : policies) {
            AuthorizationDecision outcome = evaluatePolicy(policy, request);
            if (outcome.effect() == Effect.DENY) {
                return outcome; // deny-overrides across policies
            }
            if (permit == null) {
                permit = outcome; // first permit wins (order-stable)
            }
        }
        return permit != null
                ? permit
                : new AuthorizationDecision(false, Effect.DENY, "no applicable policy", null, 0, null);
    }

    private AuthorizationDecision evaluatePolicy(Policy policy, AuthorizationRequest request) {
        AuthorizationDecision permit = null;
        for (Rule rule : policy.rules()) {
            if (conditionEvaluator.holds(rule.condition(), request)) {
                if (rule.effect() == Effect.DENY) {
                    return decision(false, Effect.DENY, "denied by rule " + rule.id(), policy, rule.id());
                }
                if (permit == null) {
                    permit = decision(true, Effect.PERMIT, "permitted by rule " + rule.id(), policy, rule.id());
                }
            }
        }
        if (permit != null) {
            return permit;
        }
        boolean allowed = policy.defaultEffect() == Effect.PERMIT;
        return decision(
                allowed,
                policy.defaultEffect(),
                "default effect (" + policy.defaultEffect() + ") of policy " + policy.id(),
                policy,
                null);
    }

    private static AuthorizationDecision decision(
            boolean allowed, Effect effect, String reason, Policy policy, String ruleId) {
        return new AuthorizationDecision(allowed, effect, reason, policy.id(), policy.version(), ruleId);
    }
}
