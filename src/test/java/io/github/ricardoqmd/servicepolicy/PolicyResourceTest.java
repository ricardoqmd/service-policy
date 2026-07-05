package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.persistence.PolicyRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * End-to-end tests for policy authoring (POST /v1/policies): author a policy over HTTP and then
 * evaluate against it over HTTP, plus the conflict, validation, and auth guards (ADR-013).
 *
 * <p>Uses {@code @TestSecurity} with the {@code authz-admin} role to satisfy the admin marker
 * check (ADR-013 §4). Tests without {@code @TestSecurity} verify the 401 guard; tests without the
 * admin role verify the 403 guard.
 */
@QuarkusTest
class PolicyResourceTest {

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
    PolicyRepository policyRepository;

    @BeforeEach
    void clean() {
        policyRepository.deleteAll();
    }

    @AfterEach
    void cleanup() {
        policyRepository.deleteAll();
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void createPolicyThenEvaluateAgainstIt() {
        // 1) author the policy over HTTP
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201)
                .body("policyId", equalTo("doc-access"))
                .body("version", equalTo(1))
                .body("active", equalTo(true));

        // 2) evaluate against it: an assigned subject is permitted
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1", "attributes": {"assignees": ["admin-user"]}}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule assigned-access"));

        // 3) a sealed resource is denied (deny-overrides)
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {
                            "type": "document", "id": "d2",
                            "attributes": {"assignees": ["admin-user"], "sealed": true}
                          }
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("denied by rule sealed-deny"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void duplicatePolicyIdReturns409() {
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(409)
                .body("error", equalTo("CONFLICT"));
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
                .post("/v1/policies")
                .then()
                .statusCode(400)
                .body("error", equalTo("BAD_REQUEST"));
    }

    @Test
    void createWithoutAuthorizationReturns401() {
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "plain-user")
    void createWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(403)
                .body("error", equalTo("FORBIDDEN"));
    }
}
