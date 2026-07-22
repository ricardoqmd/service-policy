package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadDocument;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for ADR-024 application scoping, as re-expressed by ADR-026.
 *
 * <p>Verifies that {@code app} is a first-class scoping dimension: policies from different apps are
 * fully isolated during evaluation, and listings are scoped by app. Under ADR-026 the app is the
 * first coordinate of the composite identity {@code (app, policyId)} and lives in the path, never in
 * a request body — so authoring guards now assert that a body carrying {@code app} is rejected, and
 * that the same {@code policyId} in two apps denotes two independent policies.
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

    @Inject
    ActionCatalogueRepository catalogueRepository;

    /**
     * ADR-028: the catalogue is per-app too, so isolation tests need the vocabulary declared in each
     * app they author under — including app-b, whose only role in one test is to be the app a policy
     * is NOT reachable through (a 404, which the catalogue must not turn into a 400).
     */
    @BeforeEach
    void declareCatalogues() {
        for (String app : List.of("app-a", "app-b", "app-x")) {
            ActionCatalogueTestSupport.declare(catalogueRepository, app, "resource", "read");
            ActionCatalogueTestSupport.declare(catalogueRepository, app, "other", "read");
        }
    }

    @AfterEach
    void clearAll() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
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
        lifecycleStore.create("app-a", policyA, "seed", null);
        lifecycleStore.activate("app-a", "iso-policy-a", 1, 0L, "seed", null);

        Policy policyB = new Policy(
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
        lifecycleStore.create("app-b", policyB, "seed", null);
        lifecycleStore.activate("app-b", "iso-policy-b", 1, 0L, "seed", null);

        // R_a: assignees=["test-user"], no clearanceLevel → only app-a's rule fires
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r1",
                                       "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/apps/app-a/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r1",
                                       "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/apps/app-b/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));

        // R_b: clearanceLevel=5, assignees absent → only app-b's rule fires
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r2",
                                       "attributes": {"clearanceLevel": 5}}
                        }
                        """)
                .when()
                .post("/v1/apps/app-b/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r2",
                                       "attributes": {"clearanceLevel": 5}}
                        }
                        """)
                .when()
                .post("/v1/apps/app-a/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "test-user")
    void noPolicyForAppDenies() {
        // Seed a policy for app-x but evaluate under app-z (no policy)
        seedActivePolicy("app-x", "solo-policy", "resource", Effect.PERMIT);

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r1",
                                       "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/apps/app-z/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }

    // ── Authoring guards: the app lives in the path, never in the body ────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithoutAppInBodySucceeds() {
        // ADR-026 inversion of the old 'createWithoutAppReturns400': a document with no 'app' is
        // now the ONLY valid form — the app comes from the path.
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
                .post("/v1/apps/app-a/policies")
                .then()
                .statusCode(201);

        given().when()
                .get("/v1/apps/app-a/policies/no-app-policy")
                .then()
                .statusCode(200)
                .body("app", equalTo("app-a"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithAppInBodyReturns400() {
        // The path is the single source of the scope: a body that also states an app is rejected
        // rather than reconciled, so route and payload can never disagree.
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-a",
                          "policyId": "app-in-body-policy",
                          "version": 1,
                          "resourceType": "resource",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
                .when()
                .post("/v1/apps/app-a/policies")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateWithAppInBodyReturns400() {
        // ADR-026 inversion of the old 'evaluateWithoutAppReturns400': the evaluate body is strict
        // and the app is a path coordinate, so an 'app' field is now an unknown field.
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "app-a",
                          "action": "resource:read",
                          "resource": {"type": "resource", "id": "r1"}
                        }
                        """)
                .when()
                .post("/v1/apps/app-a/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void appendWithAppInContentReturns400() {
        // Create a policy under app-a, then try to append a version whose content carries an app.
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "policyId": "append-guard-policy",
                          "version": 1,
                          "resourceType": "resource",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
                .when()
                .post("/v1/apps/app-a/policies")
                .then()
                .statusCode(201);

        String etag = given().when()
                .get("/v1/apps/app-a/policies/append-guard-policy")
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
                            "policyId": "append-guard-policy",
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
                .put("/v1/apps/app-a/policies/append-guard-policy")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void appendUnderAnotherAppDoesNotReachTheOriginalPolicy() {
        // The ADR-024 rule "a version cannot change the policy's app" survives structurally: the
        // head is addressed by (app, policyId) from the path, so appending "under app-b" simply
        // addresses a different — here nonexistent — policy instead of relocating app-a's.
        given().contentType(ContentType.JSON)
                .body("""
                        {
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
                .post("/v1/apps/app-a/policies")
                .then()
                .statusCode(201);

        String etag = given().when()
                .get("/v1/apps/app-a/policies/immutable-app-policy")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("""
                        {
                          "content": {
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
                .put("/v1/apps/app-b/policies/immutable-app-policy")
                .then()
                .statusCode(404)
                .body("code", equalTo("POLICY_NOT_FOUND"));

        // app-a's policy is untouched: still one version, still owned by app-a.
        given().when()
                .get("/v1/apps/app-a/policies/immutable-app-policy/versions")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].version", equalTo(1))
                .body("data[0].app", equalTo("app-a"));
    }

    // ── Composite identity: the same policyId in two apps is two policies ─────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void samePolicyIdInAnotherAppIsCreatedNotRejected() {
        // ADR-026 REVERSAL of the old 'createWithMismatchedAppOnOrphanHeadReturns400': claiming a
        // policyId that already exists in ANOTHER app used to be a 400 INVALID_POLICY ("app is
        // immutable"). Identity is now (app, policyId), so this is a legitimate, independent
        // policy and must be created.
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "policyId": "shared-id-policy",
                          "version": 1,
                          "resourceType": "resource",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
                .when()
                .post("/v1/apps/app-a/policies")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "policyId": "shared-id-policy",
                          "version": 1,
                          "resourceType": "other",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
                .when()
                .post("/v1/apps/app-b/policies")
                .then()
                .statusCode(201);

        // The two heads are independent: each app sees its own resourceType and its own app.
        given().when()
                .get("/v1/apps/app-a/policies/shared-id-policy")
                .then()
                .statusCode(200)
                .body("app", equalTo("app-a"))
                .body("resourceType", equalTo("resource"));

        given().when()
                .get("/v1/apps/app-b/policies/shared-id-policy")
                .then()
                .statusCode(200)
                .body("app", equalTo("app-b"))
                .body("resourceType", equalTo("other"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void orphanHeadInAnotherAppDoesNotBlockCreate() {
        // Simulate a partially-failed prior create in app-a: a head with no version. Under ADR-024
        // this made the same policyId unclaimable in app-b (400 INVALID_POLICY). Under ADR-026 the
        // upsert filter and unique indexes are per-app, so app-b's create must succeed.
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
                .post("/v1/apps/app-b/policies")
                .then()
                .statusCode(201);

        given().when()
                .get("/v1/apps/app-b/policies/conflict-policy")
                .then()
                .statusCode(200)
                .body("app", equalTo("app-b"));
    }

    // ── List scoping ──────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void catalogueFiltersByApp() {
        // '?app=' survives only on the cross-app catalogue (ADR-026 §3), where it is a genuine
        // optional filter over a collection that spans apps.
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

        // Unfiltered, the catalogue spans both apps.
        given().when()
                .get("/v1/policies")
                .then()
                .statusCode(200)
                .body("data", hasSize(3))
                .body("pagination.totalElements", equalTo(3));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void nestedListIsScopedToItsApp() {
        // On the nested route the app is an identity coordinate, not a filter: the listing is
        // scoped by the path alone.
        seedActivePolicy("app-a", "list-policy-a1", "resource", Effect.DENY);
        seedActivePolicy("app-a", "list-policy-a2", "other", Effect.DENY);
        seedActivePolicy("app-b", "list-policy-b1", "resource", Effect.DENY);

        given().when()
                .get("/v1/apps/app-a/policies")
                .then()
                .statusCode(200)
                .body("data", hasSize(2))
                .body("pagination.totalElements", equalTo(2))
                .body("data.app", everyItem(equalTo("app-a")));

        given().when()
                .get("/v1/apps/app-b/policies")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("pagination.totalElements", equalTo(1))
                .body("data[0].app", equalTo("app-b"));
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
                policyId, 1, resourceType, List.of("*"), CombiningAlgorithm.DENY_OVERRIDES, defaultEffect, rules);
        lifecycleStore.create(app, policy, "seed", null);
        lifecycleStore.activate(app, policyId, 1, 0L, "seed", null);
    }
}
