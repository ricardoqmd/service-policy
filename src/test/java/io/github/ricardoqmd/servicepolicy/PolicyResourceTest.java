package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * End-to-end tests for policy authoring (POST /v1/apps/{app}/policies): create a policy and verify
 * the write guards (conflict, validation, auth — ADR-013, ADR-019). The owning app is the path
 * (ADR-026), never a body field.
 */
@QuarkusTest
class PolicyResourceTest {

    private static final String POLICIES = "/v1/apps/test-app/policies";

    private static final String DOC_ACCESS_POLICY = """
            {
              "policyId": "doc-access",
              "version": 1,
              "resourceType": "document",
              "actions": ["*"],
              "combiningAlgorithm": "DENY_OVERRIDES",
              "defaultEffect": "DENY",
              "rules": [
                {
                  "id": "assigned-access",
                  "effect": "PERMIT",
                  "condition": {
                    "type": "comparison", "op": "IN",
                    "left": {"ref": "subject.id"}, "right": {"ref": "resource.attr.assignees"}
                  }
                },
                {
                  "id": "sealed-deny",
                  "effect": "DENY",
                  "condition": {
                    "type": "comparison", "op": "EQ",
                    "left": {"ref": "resource.attr.sealed"}, "right": {"value": true}
                  }
                }
              ]
            }
            """;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @BeforeEach
    void clean() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void createPolicyIsInactiveByDefault() {
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post(POLICIES)
                .then()
                .statusCode(201)
                .body("policyId", equalTo("doc-access"))
                .body("version", equalTo(1))
                .body("active", equalTo(false));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void duplicatePolicyIdReturns409() {
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post(POLICIES)
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post(POLICIES)
                .then()
                .statusCode(409)
                .body("code", equalTo("POLICY_ALREADY_EXISTS"));
    }

    /**
     * Identity is the composite key (app, policyId) (ADR-026): the same id in another app is a
     * different policy, not a conflict.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void samePolicyIdInAnotherAppIsCreated() {
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post(POLICIES)
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/apps/other-app/policies")
                .then()
                .statusCode(201)
                .body("policyId", equalTo("doc-access"))
                .body("version", equalTo(1))
                .body("active", equalTo(false));
    }

    /**
     * The app lives in the path and nowhere else (ADR-026): a body that also carries it is rejected
     * rather than reconciled, so a request can never state one app in the route and another in the
     * payload.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void policyDocumentCarryingAppReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "policyId": "doc-access",
                          "version": 1,
                          "resourceType": "document",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
                .when()
                .post(POLICIES)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    /**
     * The same rejection when the body's app <em>contradicts</em> the path. The field is refused for
     * existing at all, not for disagreeing — but this is the case the rule exists to make
     * unrepresentable, so it is pinned separately from the agreeing one above: a create in 'nami'
     * must never be able to claim 'kronia', not even by writing it down.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void policyDocumentCarryingAContradictoryAppReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "kronia",
                          "policyId": "doc-access",
                          "version": 1,
                          "resourceType": "document",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """)
                .when()
                .post("/v1/apps/nami/policies")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));

        // and nothing was created under either app.
        given().when().get("/v1/apps/nami/policies/doc-access").then().statusCode(404);
        given().when().get("/v1/apps/kronia/policies/doc-access").then().statusCode(404);
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void malformedPolicyReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "policyId": "bad",
                          "version": 1,
                          "resourceType": "document",
                          "actions": ["*"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": [
                            {"id": "r1", "effect": "PERMIT",
                             "condition": {"type": "comparison", "op": "NOPE",
                               "left": {"ref": "subject.id"}, "right": {"value": "x"}}}
                          ]
                        }
                        """)
                .when()
                .post(POLICIES)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"));
    }

    @Test
    void createWithoutAuthorizationReturns401() {
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post(POLICIES)
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "plain-user")
    void createWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post(POLICIES)
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }
}
