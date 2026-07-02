package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.persistence.PolicyRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * End-to-end tests for policy authoring (POST /v1/policies): author a policy over HTTP and then
 * evaluate against it over HTTP, plus the conflict, validation, and auth guards (ADR-012).
 */
@QuarkusTest
class PolicyResourceTest {

    private static final String AUTH = "Bearer " + fakeToken("test-user");

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
    void createPolicyThenEvaluateAgainstIt() {
        // 1) author the policy over HTTP
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201)
                .body("policyId", equalTo("doc-access"))
                .body("version", equalTo(1))
                .body("active", equalTo(true));

        // 2) evaluate against it over HTTP: an assigned subject is permitted
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1", "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule assigned-access"));

        // 3) a sealed resource is denied (deny-overrides)
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {
                            "type": "document", "id": "d2",
                            "attributes": {"assignees": ["test-user"], "sealed": true}
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
    void duplicatePolicyIdReturns409() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body(DOC_ACCESS_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(409)
                .body("error", equalTo("CONFLICT"));
    }

    @Test
    void malformedPolicyReturns400() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
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

    private static String fakeToken(String sub) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
