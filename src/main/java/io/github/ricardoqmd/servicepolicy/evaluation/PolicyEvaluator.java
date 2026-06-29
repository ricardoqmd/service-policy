package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.List;

/**
 * Port: authorization evaluation engine.
 *
 * <p>Implementors answer whether a subject may perform an action on a resource, and can enumerate
 * all actions permitted for a subject within an application context.
 *
 * <p>The active implementation is {@link PersistentPolicyEvaluator}, which evaluates
 * MongoDB-backed policies with the pure-domain engine (deny-overrides).
 */
public interface PolicyEvaluator {

    /**
     * Evaluates a single authorization request.
     *
     * @param subject the resolved subject identifier (from JWT).
     * @param request the evaluation request from the PEP.
     * @return the authorization decision.
     */
    Decision evaluate(String subject, EvaluationRequest request);

    /**
     * Returns all actions the subject is permitted to perform in the given application context.
     *
     * @param subject the resolved subject identifier.
     * @param app     the application context name.
     * @return list of permitted action strings; empty list if none.
     */
    List<String> permittedActions(String subject, String app);

    /**
     * Returns the version of the active policy set.
     *
     * @return policy version string.
     */
    String policyVersion();
}
