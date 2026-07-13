package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for the PEP-facing evaluation and permissions endpoints against the
 * persistence-backed evaluator. No policies are seeded here, so evaluations fall through to
 * the fail-safe default deny — proving the end-to-end wiring.
 *
 * <p>Uses {@code @TestSecurity} to supply the authenticated identity (ADR-013).
 * Tests without {@code @TestSecurity} verify that unauthenticated requests are rejected.
 */
@QuarkusTest
class EvaluationResourceTest {

    @Test
    @TestSecurity(user = "test-user")
    void evaluateWithNoApplicablePolicyDefaultsToDeny() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("no applicable policy"))
                .body("decisionId", notNullValue())
                .body("policyVersion", notNullValue())
                .body("obligations", hasSize(0));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateAcceptsSubjectAttributesWithoutError() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1", "attributes": {"area": "A"}},
                          "subjectAttributes": {"area": "A"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }

    @Test
    void evaluateWithoutAuthorizationReturns401() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateMissingActionReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "",
                          "resource": {"type": "document", "id": "d1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateMissingResourceReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read"
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateExplicitSubjectEqualToCallerIsAllowed() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"},
                          "subject": "test-user"
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(user = "caller")
    void evaluateExplicitSubjectDifferentFromCallerWithoutDelegationReturns403() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"},
                          "subject": "other-user"
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(
            user = "caller",
            roles = {"pdp-client"})
    void evaluateExplicitSubjectDifferentFromCallerWithDelegationIsAllowed() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"},
                          "subject": "other-user"
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
    void batchReturnsADecisionPerRequestAndGuardsBlankResource() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": [
                            {
                              "app": "test-app",
                              "action": "document:read",
                              "resource": {"type": "document", "id": "d1"}
                            },
                            {
                              "app": "test-app",
                              "action": "document:read"
                            }
                          ]
                        }
                        """)
                .when()
                .post("/v1/evaluate/batch")
                .then()
                .statusCode(200)
                .body("decisions", hasSize(2))
                .body("decisions[0].allowed", equalTo(false))
                .body("decisions[1].allowed", equalTo(false))
                .body("decisions[1].reason", equalTo("resource.type must not be blank"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateMissingActionFieldReturns400() {
        // action field absent → action == null branch in EvaluateResource
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateResourceWithNoTypeFieldReturns400() {
        // resource present but no type field → resource.type() == null branch
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"id": "d1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateResourceWithBlankTypeReturns400() {
        // resource.type is blank → resource.type().isBlank() == true branch
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "", "id": "d1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void batchWithMissingAppReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": [
                            {
                              "app": "test-app",
                              "action": "document:read",
                              "resource": {"type": "document", "id": "d1"}
                            },
                            {
                              "action": "document:read",
                              "resource": {"type": "document", "id": "d2"}
                            }
                          ]
                        }
                        """)
                .when()
                .post("/v1/evaluate/batch")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    void batchWithoutAuthorizationReturns401() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": [
                            {
                              "action": "document:read",
                              "resource": {"type": "document", "id": "d1"}
                            }
                          ]
                        }
                        """)
                .when()
                .post("/v1/evaluate/batch")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "test-user")
    void permissionsReturnsEmptyListForNow() {
        given().queryParam("app", "rh")
                .when()
                .get("/v1/permissions")
                .then()
                .statusCode(200)
                .body("subject", equalTo("test-user"))
                .body("permissions", hasSize(0))
                .body("policyVersion", notNullValue())
                .body("evaluatedAt", notNullValue());
    }

    @Test
    @TestSecurity(user = "test-user")
    void permissionsWithoutAppReturns400() {
        given().when().get("/v1/permissions").then().statusCode(400).body("code", equalTo("BAD_REQUEST"));
    }
}
