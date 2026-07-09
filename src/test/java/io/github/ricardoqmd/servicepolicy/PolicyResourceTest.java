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
 * End-to-end tests for policy authoring (POST /v1/policies): create a policy and verify the
 * write guards (conflict, validation, auth — ADR-013, ADR-019).
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
                .post("/v1/policies")
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
                .post("/v1/policies")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(409)
                .body("code", equalTo("POLICY_ALREADY_EXISTS"));
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
                .body("code", equalTo("INVALID_POLICY"));
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
                .body("code", equalTo("FORBIDDEN"));
    }
}
