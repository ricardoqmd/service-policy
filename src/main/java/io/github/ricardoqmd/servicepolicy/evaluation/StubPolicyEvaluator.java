package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Singleton;

import io.github.ricardoqmd.servicepolicy.config.StubConfig;

/**
 * Deterministic stub implementation of {@link PolicyEvaluator}.
 *
 * <p>Applies four rules in order using only in-memory config — no persistence, no Mongo:
 *
 * <ol>
 *   <li>Emergency override: {@code context.emergency == true} → PERMIT + audit obligation.
 *   <li>Confidential resource: {@code resource.attributes.confidencial == true} → DENY.
 *   <li>Denied verb: verb (substring after {@code :}) is in {@code denied-verbs} config → DENY.
 *   <li>Default: PERMIT.
 * </ol>
 *
 * <p>Replace with a persistence-backed evaluator in Phase 2.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed; the proxy would
// also hide constructor coverage from JaCoCo (see PR #14).
@Singleton
public class StubPolicyEvaluator implements PolicyEvaluator {

    private final StubConfig config;

    StubPolicyEvaluator(StubConfig config) {
        this.config = config;
    }

    @Override
    public Decision evaluate(String subject, EvaluationRequest request) {
        String verb = verbOf(request.action());
        String decisionId = UUID.randomUUID().toString();

        // Rule 1: emergency override
        Object emergency = request.context() != null ? request.context().get("emergency") : null;
        if (Boolean.TRUE.equals(emergency)) {
            return new Decision(
                    true,
                    "emergency override",
                    decisionId,
                    config.policyVersion(),
                    List.of(new Obligation("audit", Map.of("level", "high"))));
        }

        // Rule 2: confidential resource
        Map<String, Object> attrs =
                request.resource() != null ? request.resource().attributes() : null;
        if (attrs != null && Boolean.TRUE.equals(attrs.get("confidencial"))) {
            return new Decision(false, "confidential resource", decisionId, config.policyVersion(), List.of());
        }

        // Rule 3: denied verb
        if (config.deniedVerbs().contains(verb)) {
            return new Decision(
                    false, "action not permitted by stub policy", decisionId, config.policyVersion(), List.of());
        }

        // Rule 4: default permit
        return new Decision(true, "permitted by default stub policy", decisionId, config.policyVersion(), List.of());
    }

    @Override
    public List<String> permittedActions(String subject, String app) {
        return config.permissions().getOrDefault(app, List.of());
    }

    @Override
    public String policyVersion() {
        return config.policyVersion();
    }

    private static String verbOf(String action) {
        int colon = action.indexOf(':');
        return colon >= 0 ? action.substring(colon + 1) : action;
    }
}
