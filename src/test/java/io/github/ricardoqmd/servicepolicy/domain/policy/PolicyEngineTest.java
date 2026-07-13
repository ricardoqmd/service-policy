package io.github.ricardoqmd.servicepolicy.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.model.AuthorizationRequest;
import io.github.ricardoqmd.servicepolicy.domain.model.Resource;
import io.github.ricardoqmd.servicepolicy.domain.model.Subject;

/** Tests the policy engine + selector over the ADR-008 slice 1-3 fixture. Pure domain. */
class PolicyEngineTest {

    private final PolicyEngine engine = new PolicyEngine(new ConditionEvaluator());
    private final PolicySelector selector = new PolicySelector();

    /** document-read-access: rules 1-3 from ADR-008, deny-overrides, default deny. */
    private Policy documentReadAccess() {
        return new Policy(
                "test-app",
                "document-read-access",
                1,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(
                        new Rule(
                                "assigned-access",
                                Effect.PERMIT,
                                new Comparison(
                                        Operator.IN,
                                        new AttributeRef("subject.id"),
                                        new AttributeRef("resource.attr.assignees"))),
                        new Rule(
                                "area-scope",
                                Effect.PERMIT,
                                new Comparison(
                                        Operator.EQ,
                                        new AttributeRef("resource.attr.area"),
                                        new AttributeRef("subject.attr.area"))),
                        new Rule(
                                "sealed-deny",
                                Effect.DENY,
                                new Comparison(
                                        Operator.EQ, new AttributeRef("resource.attr.sealed"), new Literal(true)))));
    }

    private AuthorizationRequest read(
            String subjectId, String subjectArea, String resArea, List<String> assignees, boolean sealed) {
        Subject subject = new Subject(subjectId, Map.of("area", subjectArea));
        Resource resource =
                new Resource("document", "doc-1", Map.of("area", resArea, "assignees", assignees, "sealed", sealed));
        return new AuthorizationRequest("test-app", subject, "document:read", resource, Map.of());
    }

    private AuthorizationDecision evaluate(AuthorizationRequest request) {
        List<Policy> applicable = selector.select(List.of(documentReadAccess()), request);
        return engine.evaluate(applicable, request);
    }

    // ---- the four ADR-008 scenarios ----

    @Test
    void permitByAssignment() {
        // u1 is an assignee even though the area differs
        AuthorizationDecision d = evaluate(read("u1", "A", "B", List.of("u1"), false));
        assertTrue(d.allowed());
        assertEquals("assigned-access", d.matchedRuleId());
        assertEquals(1, d.policyVersion());
    }

    @Test
    void permitByArea() {
        // u2 is not an assignee but shares the resource's area
        AuthorizationDecision d = evaluate(read("u2", "A", "A", List.of("u1"), false));
        assertTrue(d.allowed());
        assertEquals("area-scope", d.matchedRuleId());
    }

    @Test
    void denyBySealedOverridesPermits() {
        // u1 is assignee AND shares area, but the resource is sealed -> deny overrides
        AuthorizationDecision d = evaluate(read("u1", "A", "A", List.of("u1"), true));
        assertFalse(d.allowed());
        assertEquals(Effect.DENY, d.effect());
        assertEquals("sealed-deny", d.matchedRuleId());
    }

    @Test
    void defaultDenyWhenNothingMatches() {
        // u9: not an assignee, different area, not sealed -> no rule matches -> default deny
        AuthorizationDecision d = evaluate(read("u9", "Z", "A", List.of("u1"), false));
        assertFalse(d.allowed());
        assertNull(d.matchedRuleId());
        assertEquals("document-read-access", d.policyId());
    }

    // ---- selection ----

    @Test
    void noApplicablePolicyForUnknownType() {
        Subject subject = new Subject("u1", Map.of());
        Resource resource = new Resource("folder", "f-1", Map.of());
        AuthorizationRequest request = new AuthorizationRequest("test-app", subject, "folder:read", resource, Map.of());

        List<Policy> applicable = selector.select(List.of(documentReadAccess()), request);
        assertTrue(applicable.isEmpty());

        AuthorizationDecision d = engine.evaluate(applicable, request);
        assertFalse(d.allowed());
        assertEquals("no applicable policy", d.reason());
        assertNull(d.policyId());
    }

    @Test
    void noApplicablePolicyForUnknownVerb() {
        List<Policy> applicable = selector.select(List.of(documentReadAccess()), read0("document:delete"));
        assertTrue(applicable.isEmpty());
    }

    @Test
    void wildcardActionApplies() {
        Policy wildcard = new Policy(
                "test-app",
                "any-document",
                1,
                "document",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of());
        List<Policy> applicable = selector.select(List.of(wildcard), read0("document:delete"));
        assertEquals(1, applicable.size());
    }

    // ---- cross-policy combining ----

    @Test
    void denyPolicyOverridesPermitPolicy() {
        Policy permitAll = new Policy(
                "test-app",
                "permit-all",
                1,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.PERMIT,
                List.of());
        Policy denySealed = documentReadAccess();

        AuthorizationRequest request = read("u1", "A", "A", List.of("u1"), true); // sealed -> deny
        AuthorizationDecision d = engine.evaluate(List.of(permitAll, denySealed), request);
        assertFalse(d.allowed());
        assertEquals("sealed-deny", d.matchedRuleId());
    }

    @Test
    void defaultPermitPolicyAllowsWhenNoRuleMatches() {
        Policy permitByDefault = new Policy(
                "test-app",
                "open-document",
                1,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.PERMIT,
                List.of());
        AuthorizationDecision d = engine.evaluate(List.of(permitByDefault), read0("document:read"));
        assertTrue(d.allowed());
        assertNull(d.matchedRuleId());
    }

    private AuthorizationRequest read0(String action) {
        Subject subject = new Subject("u1", Map.of("area", "A"));
        Resource resource =
                new Resource("document", "doc-1", Map.of("area", "A", "assignees", List.of("u1"), "sealed", false));
        return new AuthorizationRequest("test-app", subject, action, resource, Map.of());
    }
}
