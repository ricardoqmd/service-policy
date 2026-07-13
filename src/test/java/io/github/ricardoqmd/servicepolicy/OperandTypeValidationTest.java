package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for ADR-023: ordering operators must have numeric literal operands at
 * authoring time (400 INVALID_POLICY), and resolve non-numeric attribute values to deny at
 * evaluation time (never 500).
 */
@QuarkusTest
class OperandTypeValidationTest {

    private static final String ADMIN = "authz-admin";

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

    // ── Part (a): static rejection at authoring ───────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithStringLiteralOnGtReturns400() {
        given().contentType(ContentType.JSON)
                .body(policyBody("GT", "\"abc\""))
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams", notNullValue())
                .body("invalidParams[0].reason", containsString("GT"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithNumericStringLiteralOnGtReturns400() {
        // "10" (a JSON string) is not a JSON number — strict rejection per ADR-023.
        given().contentType(ContentType.JSON)
                .body(policyBody("GT", "\"10\""))
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithBooleanLiteralOnLtReturns400() {
        given().contentType(ContentType.JSON)
                .body(policyBody("LT", "true"))
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].reason", containsString("LT"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithIntegerLiteralOnGtSucceeds() {
        given().contentType(ContentType.JSON)
                .body(policyBody("GT", "10"))
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201)
                .body("policyId", equalTo("type-val-policy"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithDecimalLiteralOnGteSucceeds() {
        given().contentType(ContentType.JSON)
                .body(policyBody("GTE", "10.5"))
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithAttributeRefOnGtSucceeds() {
        // A reference operand is not validated at authoring — it may resolve to a number at runtime.
        given().contentType(ContentType.JSON)
                .body(policyBodyWithRefRight("GT"))
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void appendWithStringLiteralOnLteReturns400() {
        // Create first, then append a version with an invalid operand type.
        given().contentType(ContentType.JSON)
                .body(policyBody("GT", "5"))
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        String etag = given().when()
                .get("/v1/policies/type-val-policy")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body(appendBody("LTE", "\"not-a-number\""))
                .when()
                .put("/v1/policies/type-val-policy")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].reason", containsString("LTE"));
    }

    // ── Part (b): dynamic deny at evaluation (never 500) ─────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void evaluateWithNonNumericAttributeOnGtDeniesNot500() {
        // Policy: PERMIT if subject.attr.clearance GT 5 (literal number).
        // Request: clearance attribute arrives as a string "high" — non-numeric.
        // Expected: allowed=false (deny), NOT a 500.
        Policy policy = new Policy(
                "test-app",
                "dyn-type-policy",
                1,
                "doc",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "r",
                        Effect.PERMIT,
                        new Comparison(Operator.GT, new AttributeRef("subject.attr.clearance"), new Literal(5)))));
        lifecycleStore.create(policy, "seed", null);
        lifecycleStore.activate("dyn-type-policy", 1, 0L, "seed", null);

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "action": "doc:read",
                          "resource": {"type": "doc", "id": "d1"},
                          "subjectAttributes": {"clearance": "high"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateWithNumericAttributeOnGtPermits() {
        // Same policy — when clearance is a real number above the threshold, it should permit.
        Policy policy = new Policy(
                "test-app",
                "dyn-num-policy",
                1,
                "doc",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "r",
                        Effect.PERMIT,
                        new Comparison(Operator.GT, new AttributeRef("subject.attr.clearance"), new Literal(3)))));
        lifecycleStore.create(policy, "seed", null);
        lifecycleStore.activate("dyn-num-policy", 1, 0L, "seed", null);

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "action": "doc:read",
                          "resource": {"type": "doc", "id": "d1"},
                          "subjectAttributes": {"clearance": 5}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String policyBody(String op, String rightValue) {
        return """
                {
                  "app": "test-app",
                  "policyId": "type-val-policy",
                  "version": 1,
                  "resourceType": "doc",
                  "actions": ["*"],
                  "combiningAlgorithm": "DENY_OVERRIDES",
                  "defaultEffect": "DENY",
                  "rules": [
                    {
                      "id": "r", "effect": "PERMIT",
                      "condition": {"type": "comparison", "op": "%s",
                        "left": {"ref": "subject.attr.level"}, "right": {"value": %s}}
                    }
                  ]
                }
                """.formatted(op, rightValue);
    }

    private static String policyBodyWithRefRight(String op) {
        return """
                {
                  "app": "test-app",
                  "policyId": "type-val-policy",
                  "version": 1,
                  "resourceType": "doc",
                  "actions": ["*"],
                  "combiningAlgorithm": "DENY_OVERRIDES",
                  "defaultEffect": "DENY",
                  "rules": [
                    {
                      "id": "r", "effect": "PERMIT",
                      "condition": {"type": "comparison", "op": "%s",
                        "left": {"ref": "subject.attr.level"}, "right": {"ref": "resource.attr.minLevel"}}
                    }
                  ]
                }
                """.formatted(op);
    }

    private static String appendBody(String op, String rightValue) {
        return """
                {
                  "content": {
                    "app": "test-app",
                    "policyId": "type-val-policy",
                    "version": 2,
                    "resourceType": "doc",
                    "actions": ["*"],
                    "combiningAlgorithm": "DENY_OVERRIDES",
                    "defaultEffect": "DENY",
                    "rules": [
                      {
                        "id": "r2", "effect": "PERMIT",
                        "condition": {"type": "comparison", "op": "%s",
                          "left": {"ref": "subject.attr.level"}, "right": {"value": %s}}
                      }
                    ]
                  }
                }
                """.formatted(op, rightValue);
    }
}
