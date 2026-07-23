package io.github.ricardoqmd.servicepolicy.evaluation;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;

/**
 * Port: authorization evaluation engine.
 *
 * <p>Implementors answer whether a subject may perform an action on a resource.
 *
 * <p>The active implementation is {@link PersistentPolicyEvaluator}, which evaluates
 * MongoDB-backed policies with the pure-domain engine (deny-overrides).
 *
 * <p>Permission <em>enumeration</em> is a separate concern and deliberately not on this port: it is
 * three-valued and isolated from enforcement by construction (ADR-030), living in the
 * {@code enumeration} package. This port stays two-valued.
 */
public interface PolicyEvaluator {

    /**
     * Evaluates a single authorization request.
     *
     * @param app     the application scope, from the request path (ADR-026).
     * @param subject the resolved subject identifier (from JWT).
     * @param request the evaluation request from the PEP.
     * @return the authorization decision.
     */
    Decision evaluate(String app, String subject, EvaluationRequest request);

    /**
     * Simulates a decision against a single caller-supplied policy, with zero effect (ADR-027).
     *
     * <p>Runs the same pure decision core as {@link #evaluate} — {@code PolicySelector} then
     * {@code PolicyEngine} — but over the supplied {@code candidate} instead of the active policies
     * loaded from persistence. Nothing is read from or written to the store: it is a pure function
     * of {@code (candidate, request)}. The candidate must already have passed authoring validation
     * ({@code PolicyDocumentMapper.fromDocument}); this method does not re-validate the document.
     *
     * @param app       the application scope, from the request path (ADR-026).
     * @param subject   the resolved subject identifier (from JWT).
     * @param request   the evaluation request to test.
     * @param candidate the single policy to evaluate the request against.
     * @return the authorization decision, identical in shape to {@link #evaluate}.
     */
    Decision simulate(String app, String subject, EvaluationRequest request, Policy candidate);
}
