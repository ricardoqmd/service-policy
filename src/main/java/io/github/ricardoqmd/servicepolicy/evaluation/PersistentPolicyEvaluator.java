package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Singleton;

import io.github.ricardoqmd.servicepolicy.domain.model.AuthorizationRequest;
import io.github.ricardoqmd.servicepolicy.domain.model.Resource;
import io.github.ricardoqmd.servicepolicy.domain.model.Subject;
import io.github.ricardoqmd.servicepolicy.domain.policy.AuthorizationDecision;
import io.github.ricardoqmd.servicepolicy.domain.policy.ConditionEvaluator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.PolicyEngine;
import io.github.ricardoqmd.servicepolicy.domain.policy.PolicySelector;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;

/**
 * Persistence-backed {@link PolicyEvaluator}: loads the active policies for the resource type from
 * MongoDB via the head-pointer model (ADR-021), selects those applicable to the request, and
 * evaluates them with the pure-domain {@link PolicyEngine} (deny-overrides). Replaces the Phase 1.5
 * stub (ADR-004) per ADR-008/ADR-010.
 *
 * <p>Subject identity arrives as a parameter (resolved from the JWT); non-identity subject
 * attributes are resolved through {@link SubjectAttributeProvider} (caller-asserted in the MVP).
 *
 * <p>Evaluation chain: REST → JWT subject → PolicyLifecycleStore → PolicySelector → PolicyEngine →
 * Decision.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class PersistentPolicyEvaluator implements PolicyEvaluator {

    // TODO(bulk-permissions ADR): /v1/permissions has no policy-set version concept yet.
    private static final String POLICY_SET_VERSION = "0";

    private final PolicyLifecycleStore lifecycleStore;
    private final SubjectAttributeProvider attributeProvider;
    private final PolicySelector selector = new PolicySelector();
    private final PolicyEngine engine = new PolicyEngine(new ConditionEvaluator());

    PersistentPolicyEvaluator(PolicyLifecycleStore lifecycleStore, SubjectAttributeProvider attributeProvider) {
        this.lifecycleStore = lifecycleStore;
        this.attributeProvider = attributeProvider;
    }

    @Override
    public Decision evaluate(String app, String subject, EvaluationRequest request) {
        String decisionId = UUID.randomUUID().toString();

        if (request.resource() == null
                || request.resource().type() == null
                || request.resource().type().isBlank()) {
            return new Decision(false, "resource.type must not be blank", decisionId, POLICY_SET_VERSION, List.of());
        }

        AuthorizationRequest authzRequest = toAuthorizationRequest(app, subject, request);
        List<Policy> candidates =
                lifecycleStore.activePoliciesFor(app, authzRequest.resource().type());
        List<Policy> applicable = selector.select(candidates, authzRequest);
        AuthorizationDecision decision = engine.evaluate(applicable, authzRequest);

        return new Decision(
                decision.allowed(),
                decision.reason(),
                decisionId,
                String.valueOf(decision.policyVersion()),
                List.of()); // domain obligations deferred (ADR-008)
    }

    @Override
    public List<String> permittedActions(String subject, String app) {
        // TODO(bulk-permissions ADR): bulk permission listing is a distinct mechanism from
        // point-in-time ABAC evaluation; returning empty (fail-safe) until it has its own design.
        return List.of();
    }

    @Override
    public String policyVersion() {
        return POLICY_SET_VERSION;
    }

    private AuthorizationRequest toAuthorizationRequest(String app, String subjectId, EvaluationRequest request) {
        Map<String, Object> subjectAttrs = attributeProvider.attributesFor(subjectId, request.subjectAttributes());
        Subject subject = new Subject(subjectId, subjectAttrs);
        Resource resource = new Resource(
                request.resource().type(),
                request.resource().id(),
                request.resource().attributes());
        return new AuthorizationRequest(app, subject, request.action(), resource, request.context());
    }
}
