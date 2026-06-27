package io.github.ricardoqmd.servicepolicy.domain.policy;

/**
 * Outcome of evaluating an authorization request against the applicable policies.
 *
 * <p>Carries the explanation for audit and dry-run: which policy/version decided and
 * which rule (or the default) produced the effect. The wiring layer maps this to the
 * transport {@code Decision}.
 *
 * @param allowed       {@code true} if the final effect is PERMIT.
 * @param effect        final combined effect.
 * @param reason        human-readable explanation.
 * @param policyId      id of the deciding policy; {@code null} if no policy applied.
 * @param policyVersion version of the deciding policy; {@code 0} if no policy applied.
 * @param matchedRuleId id of the rule that produced the effect; {@code null} if defaulted.
 */
public record AuthorizationDecision(
        boolean allowed, Effect effect, String reason, String policyId, int policyVersion, String matchedRuleId) {}
