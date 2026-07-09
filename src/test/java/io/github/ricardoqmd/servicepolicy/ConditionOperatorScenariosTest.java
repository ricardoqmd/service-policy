package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.And;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operand;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Or;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

/**
 * Integration tests for condition-evaluation branches not exercised by other scenario tests.
 * Each test seeds a targeted active policy, drives the evaluate endpoint, and checks the decision
 * outcome. Tests are isolated: {@code @AfterEach} wipes all persisted state.
 *
 * <p>Covers: NOT_IN (all operand combinations), IN with non-collection right, ordering operators
 * (GT/GTE/LT/LTE — true, false, and absent-operand paths), AND/OR composites, multi-policy
 * first-PERMIT-wins, multi-rule first-PERMIT-wins, and the no-colon action path in {@link
 * io.github.ricardoqmd.servicepolicy.domain.policy.PolicySelector}.
 */
@QuarkusTest
class ConditionOperatorScenariosTest {

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @AfterEach
    void clearAll() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
    }

    // ── NOT_IN ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void notInPermitsWhenSubjectAttributeNotInCollection() {
        // NOT_IN(subject.attr.dept, resource.attr.restricted): both present, left ∉ right → true
        Condition cond = cmp(Operator.NOT_IN, attr("subject.attr.dept"), attr("resource.attr.restricted"));
        activate(new Rule("r", Effect.PERMIT, cond));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1",
                "attributes":{"restricted":["HR","Legal"]}},"subjectAttributes":{"dept":"IT"}}
                """).body("allowed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void notInDeniesWhenSubjectAttributeInCollection() {
        // NOT_IN: both present, left ∈ right → false → default DENY
        Condition cond = cmp(Operator.NOT_IN, attr("subject.attr.dept"), attr("resource.attr.restricted"));
        activate(new Rule("r", Effect.PERMIT, cond));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1",
                "attributes":{"restricted":["HR","Legal"]}},"subjectAttributes":{"dept":"HR"}}
                """).body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void notInWithAbsentLeftDoesNotPermit() {
        // NOT_IN: left is null (absent attribute) — notIn short-circuits on null left (ADR-011)
        Condition cond = cmp(Operator.NOT_IN, attr("subject.attr.dept"), attr("resource.attr.restricted"));
        activate(new Rule("r", Effect.PERMIT, cond));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1",
                "attributes":{"restricted":["HR","Legal"]}}}
                """).body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void notInWithAbsentRightDoesNotPermit() {
        // NOT_IN: right is null (attribute absent) — notIn returns false on null right (ADR-011)
        Condition cond = cmp(Operator.NOT_IN, attr("subject.attr.dept"), attr("resource.attr.restricted"));
        activate(new Rule("r", Effect.PERMIT, cond));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"dept":"IT"}}
                """).body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void notInWithNonCollectionRightPermitsVacuously() {
        // NOT_IN: right is a String, not a Collection — !(right instanceof Collection) → true → PERMIT
        Condition cond = cmp(Operator.NOT_IN, attr("subject.attr.dept"), attr("resource.attr.zone"));
        activate(new Rule("r", Effect.PERMIT, cond));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1",
                "attributes":{"zone":"restricted"}},"subjectAttributes":{"dept":"IT"}}
                """).body("allowed", equalTo(true));
    }

    // ── IN with non-collection right ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void inWithNonCollectionRightDoesNotPermit() {
        // IN: right is a String (not a Collection) — right instanceof Collection → false → false
        Condition cond = cmp(Operator.IN, attr("subject.attr.dept"), attr("resource.attr.zone"));
        activate(new Rule("r", Effect.PERMIT, cond));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1",
                "attributes":{"zone":"IT"}},"subjectAttributes":{"dept":"IT"}}
                """).body("allowed", equalTo(false));
    }

    // ── GTE ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void gtePermitsWhenClearanceMeetsThreshold() {
        // GTE: clearance=5, threshold=5 → 5 >= 5 → true → PERMIT
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.GTE, attr("subject.attr.clearance"), lit(5))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"clearance":5}}
                """).body("allowed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void gteDeniesWhenClearanceBelowThreshold() {
        // GTE: clearance=4, threshold=5 → 4 >= 5 → false → default DENY
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.GTE, attr("subject.attr.clearance"), lit(5))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"clearance":4}}
                """).body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void gteWithAbsentClearanceDoesNotPermit() {
        // GTE: left is null (absent) — bothPresent false → GTE false without calling compareNumbers
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.GTE, attr("subject.attr.clearance"), lit(5))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(false));
    }

    // ── LT ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void ltPermitsWhenClearanceBelowCeiling() {
        // LT: clearance=5, ceiling=10 → 5 < 10 → true → PERMIT
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.LT, attr("subject.attr.clearance"), lit(10))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"clearance":5}}
                """).body("allowed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void ltDeniesWhenClearanceAtCeiling() {
        // LT: clearance=10, ceiling=10 → 10 < 10 → false → default DENY
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.LT, attr("subject.attr.clearance"), lit(10))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"clearance":10}}
                """).body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void ltWithAbsentClearanceDoesNotPermit() {
        // LT: left is null — bothPresent false → LT false
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.LT, attr("subject.attr.clearance"), lit(10))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(false));
    }

    // ── LTE ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void ltePermitsWhenClearanceAtLimit() {
        // LTE: clearance=5, limit=5 → 5 <= 5 → true → PERMIT
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.LTE, attr("subject.attr.clearance"), lit(5))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"clearance":5}}
                """).body("allowed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void lteDeniesWhenClearanceAboveLimit() {
        // LTE: clearance=6, limit=5 → 6 <= 5 → false → default DENY
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.LTE, attr("subject.attr.clearance"), lit(5))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"clearance":6}}
                """).body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void lteWithAbsentClearanceDoesNotPermit() {
        // LTE: left is null — bothPresent false → LTE false
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.LTE, attr("subject.attr.clearance"), lit(5))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(false));
    }

    // ── GT ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void gtPermitsWhenClearanceAboveThreshold() {
        // GT: clearance=5, threshold=3 → 5 > 3 → true → PERMIT
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.GT, attr("subject.attr.clearance"), lit(3))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"clearance":5}}
                """).body("allowed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void gtDeniesWhenClearanceAtThreshold() {
        // GT: clearance=3, threshold=3 → 3 > 3 → false → default DENY
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.GT, attr("subject.attr.clearance"), lit(3))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"subjectAttributes":{"clearance":3}}
                """).body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void gtWithAbsentClearanceDoesNotPermit() {
        // GT: left is null — bothPresent false → GT false (no throw; contrast non-numeric operand)
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.GT, attr("subject.attr.clearance"), lit(3))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(false));
    }

    // ── IN with absent left ───────────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void inWithAbsentLeftDoesNotPermit() {
        // IN: left is null (absent attribute) — left != null → false → IN false (ADR-011)
        Condition cond = cmp(Operator.IN, attr("subject.attr.dept"), attr("resource.attr.owners"));
        activate(new Rule("r", Effect.PERMIT, cond));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1",
                "attributes":{"owners":["alice","bob"]}}}
                """).body("allowed", equalTo(false));
    }

    // ── Fixed-path lookups (resource.type, resource.id, context.*) ───────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void conditionOnFixedResourceTypePathWorks() {
        // lookup("resource.type"): exercises the switch case "resource.type" in lookup()
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.EQ, attr("resource.type"), lit("doc"))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void conditionOnFixedResourceIdPathWorks() {
        // lookup("resource.id"): exercises the switch case "resource.id" in lookup()
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.EQ, attr("resource.id"), lit("d1"))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void conditionOnContextAttributeWorks() {
        // lookup("context.*"): exercises the context.startsWith branch in lookup()
        activate(new Rule("r", Effect.PERMIT, cmp(Operator.EQ, attr("context.env"), lit("prod"))));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},"context":{"env":"prod"}}
                """).body("allowed", equalTo(true));
    }

    // ── AND / OR composite conditions ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void andPermitsOnlyWhenAllSubConditionsHold() {
        // AND(EQ(role,"reviewer"), GTE(clearance,3)): exercises And arm in holds() switch
        Condition cond = new And(List.of(
                cmp(Operator.EQ, attr("subject.attr.role"), lit("reviewer")),
                cmp(Operator.GTE, attr("subject.attr.clearance"), lit(3))));
        activate(new Rule("r", Effect.PERMIT, cond));

        // All sub-conditions hold → PERMIT
        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},
                "subjectAttributes":{"role":"reviewer","clearance":5}}
                """).body("allowed", equalTo(true));

        // One sub-condition fails → AND short-circuits to false → default DENY
        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},
                "subjectAttributes":{"role":"reviewer","clearance":1}}
                """).body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void orPermitsWhenAnySubConditionHolds() {
        // OR(EQ(role,"admin"), EQ(role,"reviewer")): exercises Or arm in holds() switch
        Condition cond = new Or(List.of(
                cmp(Operator.EQ, attr("subject.attr.role"), lit("admin")),
                cmp(Operator.EQ, attr("subject.attr.role"), lit("reviewer"))));
        activate(new Rule("r", Effect.PERMIT, cond));

        // At least one sub-condition holds → PERMIT
        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},
                "subjectAttributes":{"role":"reviewer"}}
                """).body("allowed", equalTo(true));

        // No sub-condition holds → default DENY
        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"},
                "subjectAttributes":{"role":"viewer"}}
                """).body("allowed", equalTo(false));
    }

    // ── PolicyEngine: first-PERMIT-wins across two PERMIT policies ────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void secondPermitPolicyIsIgnoredWhenFirstAlreadyPermitted() {
        // Two policies with defaultEffect=PERMIT, no rules. PolicyEngine iterates both:
        // first → permit (null → assign), second → permit (non-null → skip). Outcome: allowed.
        activateFull(
                new Policy("pa", 1, "doc", List.of("*"), CombiningAlgorithm.DENY_OVERRIDES, Effect.PERMIT, List.of()));
        activateFull(
                new Policy("pb", 1, "doc", List.of("*"), CombiningAlgorithm.DENY_OVERRIDES, Effect.PERMIT, List.of()));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(true));
    }

    // ── PolicyEngine: first-PERMIT-wins across two matching rules in the same policy ──────────────

    @Test
    @TestSecurity(user = "test-user")
    void secondMatchingPermitRuleInPolicyIsIgnored() {
        // Two PERMIT rules that both match; evaluatePolicy stores first and skips second.
        Policy policy = new Policy(
                "two-permits",
                1,
                "doc",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(
                        new Rule("first", Effect.PERMIT, cmp(Operator.EQ, lit(true), lit(true))),
                        new Rule("second", Effect.PERMIT, cmp(Operator.EQ, lit(true), lit(true)))));
        activateFull(policy);

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(true)).body("reason", equalTo("permitted by rule first"));
    }

    // ── PolicySelector: verbOf with action that has no colon ─────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void actionWithoutColonMatchesVerbDirectly() {
        // verbOf("read") returns "read" (colon < 0 branch: action returned as-is)
        activateFull(new Policy(
                "verb-policy", 1, "doc", List.of("read"), CombiningAlgorithm.DENY_OVERRIDES, Effect.PERMIT, List.of()));

        eval("""
                {"action":"read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(true));
    }

    // ── Policy.appliesTo: specific verb matching (no wildcard) ────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void verbBasedPolicyMatchesExactVerb() {
        // actions=["read"]: contains("*")→false, contains("read")→true → policy selected
        activateFull(new Policy(
                "verb-exact", 1, "doc", List.of("read"), CombiningAlgorithm.DENY_OVERRIDES, Effect.PERMIT, List.of()));

        eval("""
                {"action":"doc:read","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(true));
    }

    @Test
    @TestSecurity(user = "test-user")
    void verbBasedPolicyDoesNotMatchDifferentVerb() {
        // actions=["read"]: contains("*")→false, contains("write")→false → policy not selected
        activateFull(new Policy(
                "verb-exact", 1, "doc", List.of("read"), CombiningAlgorithm.DENY_OVERRIDES, Effect.PERMIT, List.of()));

        eval("""
                {"action":"doc:write","resource":{"type":"doc","id":"d1"}}
                """).body("allowed", equalTo(false)).body("reason", equalTo("no applicable policy"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void activate(Rule rule) {
        activateFull(new Policy(
                "policy", 1, "doc", List.of("*"), CombiningAlgorithm.DENY_OVERRIDES, Effect.DENY, List.of(rule)));
    }

    private void activateFull(Policy policy) {
        lifecycleStore.create(policy, "seed", null);
        lifecycleStore.activate(policy.id(), 1, 0L, "seed", null);
    }

    private static ValidatableResponse eval(String body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200);
    }

    private static Comparison cmp(Operator op, Operand left, Operand right) {
        return new Comparison(op, left, right);
    }

    private static AttributeRef attr(String path) {
        return new AttributeRef(path);
    }

    private static Literal lit(Object value) {
        return new Literal(value);
    }
}
