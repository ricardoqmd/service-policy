package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
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
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadDocument;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for ADR-024: application scoping.
 *
 * <p>Verifies that {@code app} is a first-class scoping dimension: policies from different apps are
 * fully isolated during evaluation, the list endpoint filters by app, and authoring rejects missing
 * or mismatched app values.
 */
@QuarkusTest
class ApplicationScopingTest {

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

    // ── Evaluation isolation ──────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void appAPermitDoesNotLeakToAppB() {
        // app-a: PERMIT when subject.id IN resource.attr.assignees
        // app-b: PERMIT when resource.attr.clearanceLevel EQ 5  (different attribute/condition)
        // R_a fires only app-a's rule; R_b fires only app-b's rule.
        // If the app filter breaks in either direction, one of the four assertions below fails.
        Policy policyA = new Policy(
                "app-a",
                "iso-policy-a",
                1,
                "resource",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "match-by-assignee",
                        Effect.PERMIT,
                        new Comparison(
                                Operator.IN,
                                new AttributeRef("subject.id"),
                                new AttributeRef("resource.attr.assignees")))));
        lifecycleStore.create(policyA, "seed", null);
        lifecycleStore.activate("iso-policy-a", 1, 0L, "seed", null);

        Policy policyB = new Policy(
                "app-b",
                "iso-policy-b",
                1,
                "resource",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "match-by-clearance",
                        Effect.PERMIT,
                        new Comparison(
                                Operator.EQ, new AttributeRef("resource.attr.clearanceLevel"), new Literal(5)))));
        lifecycleStore.create(policyB, "seed", null);
        lifecycleStore.activate("iso-policy-b", 1, 0L, "seed", null);

        // R_a: assignees=["test-user"], no clearanceLevel → only app-a's rule fires
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-a",
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r1",
                                       "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-b",
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r1",
                                       "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));

        // R_b: clearanceLevel=5, assignees absent → only app-b's rule fires
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-b",
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r2",
                                       "attributes": {"clearanceLevel": 5}}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-a",
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r2",
                                       "attributes": {"clearanceLevel": 5}}
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
    void noPolicyForAppDenies() {
        // Seed a policy for app-x but evaluate as app-z (no policy)
        seedActivePolicy("app-x", "solo-policy", "resource", Effect.PERMIT);

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-z",
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r1",
                                       "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }

    // ── Authoring guards ──────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithoutAppReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "policyId": "no-app-policy",
                          "version": 1,
                          "resourceType": "resource",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
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
    void appendWithDifferentAppReturns400() {
        // Create policy under app-a, then try to append a version under app-b
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-a",
                          "policyId": "immutable-app-policy",
                          "version": 1,
                          "resourceType": "resource",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        String etag = given().when()
                .get("/v1/policies/immutable-app-policy")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("""
                        {
                          "content": {
                            "app": "app-b",
                            "policyId": "immutable-app-policy",
                            "version": 2,
                            "resourceType": "resource",
                            "actions": ["*"],
                            "combiningAlgorithm": "DENY_OVERRIDES",
                            "defaultEffect": "DENY",
                            "rules": []
                          }
                        }
                        """)
                .when()
                .put("/v1/policies/immutable-app-policy")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithMismatchedAppOnOrphanHeadReturns400() {
        // Simulate a partially-failed prior create: head exists under app-a but the version
        // insert previously failed, leaving no version. Trying to claim the same policyId
        // under app-b must be rejected — not silently accepted, which would corrupt state.
        PolicyHeadDocument orphan = new PolicyHeadDocument();
        orphan.policyId = "conflict-policy";
        orphan.app = "app-a";
        orphan.resourceType = "resource";
        orphan.revision = 0L;
        orphan.audit = new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
        headRepository.persist(orphan);

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-b",
                          "policyId": "conflict-policy",
                          "version": 1,
                          "resourceType": "resource",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    // ── Evaluate missing app guard ────────────────────────────────────────────

    @Test
    @TestSecurity(user = "test-user")
    void evaluateWithoutAppReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    // ── List filter ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void listFiltersByApp() {
        seedActivePolicy("app-a", "list-policy-a1", "resource", Effect.DENY);
        seedActivePolicy("app-a", "list-policy-a2", "other", Effect.DENY);
        seedActivePolicy("app-b", "list-policy-b1", "resource", Effect.DENY);

        given().when()
                .get("/v1/policies?app=app-a")
                .then()
                .statusCode(200)
                .body("data", hasSize(2))
                .body("pagination.totalElements", equalTo(2));

        given().when()
                .get("/v1/policies?app=app-b")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("pagination.totalElements", equalTo(1));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void seedActivePolicy(String app, String policyId, String resourceType, Effect defaultEffect) {
        List<Rule> rules = defaultEffect == Effect.PERMIT
                ? List.of(new Rule(
                        "assigned-access",
                        Effect.PERMIT,
                        new Comparison(
                                Operator.IN,
                                new AttributeRef("subject.id"),
                                new AttributeRef("resource.attr.assignees"))))
                : List.of();
        Policy policy = new Policy(
                app, policyId, 1, resourceType, List.of("*"), CombiningAlgorithm.DENY_OVERRIDES, defaultEffect, rules);
        lifecycleStore.create(policy, "seed", null);
        lifecycleStore.activate(policyId, 1, 0L, "seed", null);
    }
}
