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
 * <p>The application scope is a path coordinate (ADR-026): requests go to
 * {@code /v1/apps/{app}/...} and no request body carries {@code app}.
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
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"}
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate")
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
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1", "attributes": {"area": "A"}},
                          "subjectAttributes": {"area": "A"}
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate")
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
                .post("/v1/apps/test-app/evaluate")
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
                .post("/v1/apps/test-app/evaluate")
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
                .post("/v1/apps/test-app/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void evaluateWithAppInBodyReturns400() {
        // The app is the path coordinate (ADR-026); a body that still claims one is rejected
        // rather than silently reconciled with the route.
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "test-app",
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"}
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate")
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
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"},
                          "subject": "test-user"
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate")
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
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"},
                          "subject": "other-user"
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate")
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
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"},
                          "subject": "other-user"
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate")
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
                              "action": "document:read",
                              "resource": {"type": "document", "id": "d1"}
                            },
                            {
                              "action": "document:read"
                            }
                          ]
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
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
                .post("/v1/apps/test-app/evaluate")
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
                .post("/v1/apps/test-app/evaluate")
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
                .post("/v1/apps/test-app/evaluate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void batchWithAppInAnItemReturns400() {
        // A single item claiming its own app fails the whole batch: the scope is the path's
        // (ADR-026), and no item may override it.
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": [
                            {
                              "action": "document:read",
                              "resource": {"type": "document", "id": "d1"}
                            },
                            {
                              "app": "other-app",
                              "action": "document:read",
                              "resource": {"type": "document", "id": "d2"}
                            }
                          ]
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
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
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "test-user")
    void permissionsForAnAppWithNoCatalogueIsAnEmptyList() {
        // ADR-030: an app with no action catalogue has nothing to enumerate, so the answer is a
        // valid computed resource with an empty list — not an error. This suite seeds no catalogue
        // for 'rh'. The full three-outcome behaviour lives in PermissionsResourceTest.
        given().when()
                .get("/v1/apps/rh/permissions")
                .then()
                .statusCode(200)
                .body("subject", equalTo("test-user"))
                .body("app", equalTo("rh"))
                .body("permissions", hasSize(0))
                .body("generatedAt", notNullValue());
    }

    @Test
    @TestSecurity(user = "test-user")
    void permissionsWithoutAppInPathIsNotRouted() {
        // Was: "?app= missing → 400". Under ADR-026 the app is a path segment, so an app-less
        // permissions request is not a bad request — it is not a route at all.
        given().when().get("/v1/permissions").then().statusCode(404);
    }
}
