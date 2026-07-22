package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/**
 * End-to-end tests for {@code POST /v1/apps/{app}/policies:simulate} (ADR-027): a dry-run of the
 * decision engine against a policy document supplied in the request body, with zero effect on
 * stored state.
 *
 * <p>The heart of the suite is <em>zero effect</em> (the store is untouched after a simulation) and
 * <em>authoring validation</em> (the candidate is validated exactly as on create before any
 * evaluation runs). Isolation is verified too: the simulation only ever sees the supplied document,
 * never the app's active policies.
 *
 * <p>URL encoding is turned off for these requests so the {@code :simulate} colon travels on the
 * wire as a raw {@code :} (a legal path character, RFC 3986 §3.3) rather than RestAssured's default
 * {@code %3A}, matching how a real client addresses the sub-resource action.
 */
@QuarkusTest
class SimulationResourceTest {

    private static final String APP = "test-app";
    private static final String ADMIN = "authz-admin";
    private static final String SIMULATE = "/v1/apps/{app}/policies:simulate";

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @BeforeEach
    void clean() {
        RestAssured.urlEncodingEnabled = false;
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
        // ADR-028: simulate validates the candidate exactly as create does, so the drafts below —
        // all ["*"] on 'document' — need the app's vocabulary declared just like a real create would.
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
    }

    @AfterEach
    void cleanup() {
        RestAssured.urlEncodingEnabled = true;
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
    }

    // ── The document is evaluated, not the head ──────────────────────────────

    /**
     * A draft that PERMITS returns allowed=true even though NO active version exists that would
     * permit it — proving the engine evaluated the document from the body, not a stored head.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void simulateDraftThatPermitsIsAllowedWithNoActivePolicy() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule assigned-access"))
                .body("decisionId", notNullValue());

        // and nothing was created by the simulation.
        assertEquals(0, headRepository.count());
        assertEquals(0, versionRepository.count());
    }

    /** A draft that DENIES (subject is not an assignee → default deny) returns allowed=false. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void simulateDraftThatDeniesIsNotAllowed() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), requestWithAssignee("someone-else")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("default effect (DENY) of policy draft-policy"));
    }

    // ── Zero effect (the critical property) ──────────────────────────────────

    /**
     * Simulating a policy whose id does not exist must leave the store exactly as it was: no head,
     * no version created, and the policy still absent afterwards. A GET of the state before and
     * after the simulation is identical (404 → 404).
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void simulationHasZeroEffectOnTheStore() {
        // Before: the ghost policy does not exist and the store is empty.
        given().when()
                .get("/v1/apps/{app}/policies/{id}", APP, "draft-policy")
                .then()
                .statusCode(404);
        assertEquals(0, headRepository.count());
        assertEquals(0, versionRepository.count());

        // Simulate a permitting draft — it decides, but must persist nothing.
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));

        // After: identical state — no head, no version, the ghost policy still absent.
        given().when()
                .get("/v1/apps/{app}/policies/{id}", APP, "draft-policy")
                .then()
                .statusCode(404);
        assertEquals(0, headRepository.count());
        assertEquals(0, versionRepository.count());
    }

    /**
     * Zero effect must also hold when a real active policy already exists: its head, version count
     * and active pointer are unchanged by a simulation against a different draft.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void simulationDoesNotDisturbAnExistingActivePolicy() {
        lifecycleStore.create(APP, docAccessPolicy(), "seed", "seed");
        lifecycleStore.activate(APP, "doc-access", 1, 0L, "seed", "seed");

        String etagBefore = given().when()
                .get("/v1/apps/{app}/policies/{id}", APP, "doc-access")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
        long headsBefore = headRepository.count();
        long versionsBefore = versionRepository.count();

        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(200);

        // Same ETag (revision), same counts — the active pointer and history are untouched.
        given().when()
                .get("/v1/apps/{app}/policies/{id}", APP, "doc-access")
                .then()
                .statusCode(200)
                .header("ETag", equalTo(etagBefore));
        assertEquals(headsBefore, headRepository.count());
        assertEquals(versionsBefore, versionRepository.count());
    }

    // ── Authoring validation runs before evaluation ──────────────────────────

    /**
     * A malformed candidate — an ordering operator (GT) with a non-numeric literal (ADR-023) — is
     * rejected with 400 INVALID_POLICY before any evaluation runs. The simulator never evaluates a
     * document that could not have been created.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void simulateMalformedDocumentReturns400InvalidPolicy() {
        String badPolicy = """
                {
                  "policyId": "bad", "version": 1, "resourceType": "document",
                  "actions": ["*"], "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                  "rules": [
                    {"id": "r", "effect": "PERMIT",
                     "condition": {"type": "comparison", "op": "GT",
                       "left": {"ref": "subject.attr.level"}, "right": {"value": "abc"}}}
                  ]
                }
                """;
        given().contentType(ContentType.JSON)
                .body(simulateBody(badPolicy, requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    /** app in the policy document → 400 INVALID_POLICY (same treatment as create, ADR-026). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void policyDocumentCarryingAppReturns400InvalidPolicy() {
        String policyWithApp = """
                {
                  "app": "test-app",
                  "policyId": "draft-policy", "version": 1, "resourceType": "document",
                  "actions": ["*"], "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                  "rules": []
                }
                """;
        given().contentType(ContentType.JSON)
                .body(simulateBody(policyWithApp, requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    /** app in the evaluation request → 400 BAD_REQUEST (same treatment as evaluate, ADR-026). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void requestCarryingAppReturns400BadRequest() {
        String body = """
                {
                  "policy": %s,
                  "request": {
                    "app": "test-app",
                    "action": "document:read",
                    "resource": {"type": "document", "id": "d1", "attributes": {"assignees": ["admin-user"]}}
                  }
                }
                """.formatted(permitDraft());
        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    // ── Admin gate ───────────────────────────────────────────────────────────

    @Test
    void simulateWithoutAuthenticationReturns401() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "plain-user")
    void simulateWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), requestWithAssignee("plain-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    // ── Isolation ────────────────────────────────────────────────────────────

    /**
     * The simulation sees ONLY the supplied document. An active policy that WOULD permit the request
     * does not leak in: a draft with a default-deny outcome returns allowed=false even though the
     * app's active doc-access policy would have permitted the same request.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void simulationDoesNotMixInTheAppsActivePolicies() {
        // Seed an ACTIVE policy that permits admin-user (an assignee) for the same request.
        lifecycleStore.create(APP, docAccessPolicy(), "seed", "seed");
        lifecycleStore.activate(APP, "doc-access", 1, 0L, "seed", "seed");

        // Simulate a DIFFERENT draft that denies (default deny, no matching rule) the same request.
        String denyDraft = """
                {
                  "policyId": "deny-draft", "version": 1, "resourceType": "document",
                  "actions": ["*"], "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                  "rules": []
                }
                """;
        given().contentType(ContentType.JSON)
                .body(simulateBody(denyDraft, requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(200)
                // If active policies had leaked in, doc-access would have permitted this. They did not.
                .body("allowed", equalTo(false))
                .body("reason", equalTo("default effect (DENY) of policy deny-draft"));
    }

    // ── Response shape mirrors /evaluate ─────────────────────────────────────

    /** The response is a Decision identical in shape to /evaluate, so the PAP reuses its render. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void responseIsADecisionInTheSameShapeAsEvaluate() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(200)
                .body("allowed", notNullValue())
                .body("reason", notNullValue())
                .body("decisionId", notNullValue())
                .body("policyVersion", notNullValue())
                .body("obligations", notNullValue());
    }

    // ── Request-body guards (each rejection branch of simulate) ──────────────

    /** A whole-body {@code null} → the 'policy' guard rejects it (body == null branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void nullBodyReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body("null")
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** No 'policy' field → the 'policy' guard rejects it (policy == null branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void missingPolicyReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body("{\"request\": %s}".formatted(requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** An empty 'policy' object → the 'policy' guard rejects it (policy.isEmpty() branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void emptyPolicyReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body("{\"policy\": {}, \"request\": %s}".formatted(requestWithAssignee("admin-user")))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** No 'request' field → the 'request' guard rejects it (request == null branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void missingRequestReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body("{\"policy\": %s}".formatted(permitDraft()))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** No 'action' in the request → the 'action' guard rejects it (action == null branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void missingActionReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), "{\"resource\": {\"type\": \"document\", \"id\": \"d1\"}}"))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** A blank 'action' → the 'action' guard rejects it (action.isBlank() branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void blankActionReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(
                        permitDraft(), "{\"action\": \"   \", \"resource\": {\"type\": \"document\", \"id\": \"d1\"}}"))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** No 'resource' in the request → the 'resource' guard rejects it (resource == null branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void missingResourceReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), "{\"action\": \"document:read\"}"))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** A resource with no 'type' → the 'resource' guard rejects it (resource.type() == null branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void missingResourceTypeReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(permitDraft(), "{\"action\": \"document:read\", \"resource\": {\"id\": \"d1\"}}"))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** A blank resource 'type' → the 'resource' guard rejects it (resource.type().isBlank() branch). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void blankResourceTypeReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(
                        permitDraft(),
                        "{\"action\": \"document:read\", \"resource\": {\"type\": \"   \", \"id\": \"d1\"}}"))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String simulateBody(String policyJson, String requestJson) {
        return """
                {
                  "policy": %s,
                  "request": %s
                }
                """.formatted(policyJson, requestJson);
    }

    /** A permitting draft: PERMIT if subject.id is in resource.attr.assignees; default DENY. */
    private static String permitDraft() {
        return """
                {
                  "policyId": "draft-policy", "version": 1, "resourceType": "document",
                  "actions": ["*"], "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                  "rules": [
                    {"id": "assigned-access", "effect": "PERMIT",
                     "condition": {"type": "comparison", "op": "IN",
                       "left": {"ref": "subject.id"}, "right": {"ref": "resource.attr.assignees"}}}
                  ]
                }
                """;
    }

    private static String requestWithAssignee(String assignee) {
        return """
                {
                  "action": "document:read",
                  "resource": {"type": "document", "id": "d1", "attributes": {"assignees": ["%s"]}}
                }
                """.formatted(assignee);
    }

    /** An active domain policy that PERMITS an assignee (deny-overrides, default DENY). */
    private static Policy docAccessPolicy() {
        Rule assignedAccess = new Rule(
                "assigned-access",
                Effect.PERMIT,
                new Comparison(
                        Operator.IN, new AttributeRef("subject.id"), new AttributeRef("resource.attr.assignees")));
        return new Policy(
                "doc-access",
                1,
                "document",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(assignedAccess));
    }
}
