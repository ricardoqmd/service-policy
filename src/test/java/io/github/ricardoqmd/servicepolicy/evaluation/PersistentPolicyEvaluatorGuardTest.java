package io.github.ricardoqmd.servicepolicy.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Branch coverage for the resource guard shared by {@link PolicyEvaluator#evaluate} and
 * {@link PolicyEvaluator#simulate}: {@code resource == null || resource.type() == null ||
 * resource.type().isBlank()} short-circuits to a deny decision before any candidate lookup or
 * engine run.
 *
 * <p>The evaluator is exercised directly (not over HTTP) because the REST layer pre-validates the
 * resource — {@code EvaluateResource} on the single path and {@code SimulationResource} both reject
 * a blank resource with 400 before delegating — so this guard is only reachable from callers that do
 * not (the batch path, and here the injected bean). This is an integration test ({@code @QuarkusTest}
 * with {@code @Inject}) so the branches count toward quarkus-jacoco coverage.
 */
@QuarkusTest
class PersistentPolicyEvaluatorGuardTest {

    private static final String APP = "test-app";
    private static final String SUBJECT = "test-user";
    private static final String BLANK_RESOURCE_REASON = "resource.type must not be blank";

    @Inject
    PolicyEvaluator evaluator;

    // ── evaluate: the guard's three conditions ───────────────────────────────

    @Test
    void evaluateWithNullResourceDenies() {
        Decision decision = evaluator.evaluate(APP, SUBJECT, request(null));
        assertFalse(decision.allowed());
        assertEquals(BLANK_RESOURCE_REASON, decision.reason());
    }

    @Test
    void evaluateWithNullResourceTypeDenies() {
        Decision decision = evaluator.evaluate(APP, SUBJECT, request(new ResourceRef(null, "d1", null)));
        assertFalse(decision.allowed());
        assertEquals(BLANK_RESOURCE_REASON, decision.reason());
    }

    @Test
    void evaluateWithBlankResourceTypeDenies() {
        Decision decision = evaluator.evaluate(APP, SUBJECT, request(new ResourceRef("   ", "d1", null)));
        assertFalse(decision.allowed());
        assertEquals(BLANK_RESOURCE_REASON, decision.reason());
    }

    // ── simulate: the same guard, independently reached ──────────────────────

    @Test
    void simulateWithNullResourceDenies() {
        Decision decision = evaluator.simulate(APP, SUBJECT, request(null), candidate());
        assertFalse(decision.allowed());
        assertEquals(BLANK_RESOURCE_REASON, decision.reason());
    }

    @Test
    void simulateWithNullResourceTypeDenies() {
        Decision decision = evaluator.simulate(APP, SUBJECT, request(new ResourceRef(null, "d1", null)), candidate());
        assertFalse(decision.allowed());
        assertEquals(BLANK_RESOURCE_REASON, decision.reason());
    }

    @Test
    void simulateWithBlankResourceTypeDenies() {
        Decision decision = evaluator.simulate(APP, SUBJECT, request(new ResourceRef("   ", "d1", null)), candidate());
        assertFalse(decision.allowed());
        assertEquals(BLANK_RESOURCE_REASON, decision.reason());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static EvaluationRequest request(ResourceRef resource) {
        return new EvaluationRequest("document:read", resource, null, null, null);
    }

    /** A valid candidate; the guard returns before it is ever consulted. */
    private static Policy candidate() {
        Rule assignedAccess = new Rule(
                "assigned-access",
                Effect.PERMIT,
                new Comparison(
                        Operator.IN, new AttributeRef("subject.id"), new AttributeRef("resource.attr.assignees")));
        return new Policy(
                "draft-policy",
                1,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(assignedAccess));
    }
}
