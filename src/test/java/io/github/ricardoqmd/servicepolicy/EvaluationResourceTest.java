package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for the PEP-facing evaluation and permissions endpoints.
 *
 * <p>Uses a fake unsigned JWT for the {@code Authorization} header since signature verification is
 * deferred to Phase 3 (ADR-003).
 */
@QuarkusTest
class EvaluationResourceTest {

    private static final String AUTH = "Bearer " + fakeToken("test-user");

    @Test
    void evaluatePermitAction() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "empleado:read",
                          "resource": {"type": "empleado", "id": "emp-1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", notNullValue())
                .body("decisionId", notNullValue())
                .body("policyVersion", notNullValue());
    }

    @Test
    void evaluateDenyByVerb() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "empleado:delete",
                          "resource": {"type": "empleado", "id": "emp-1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", notNullValue());
    }

    @Test
    void evaluateDenyByConfidentialAttribute() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "empleado:read",
                          "resource": {
                            "type": "documento",
                            "id": "doc-1",
                            "attributes": {"confidencial": true}
                          }
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("confidential resource"));
    }

    @Test
    void evaluateEmergencyOverride() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "empleado:delete",
                          "resource": {"type": "empleado", "id": "emp-99"},
                          "context": {"emergency": true}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("emergency override"))
                .body("obligations", hasSize(greaterThan(0)));
    }

    @Test
    void evaluateWithoutAuthorizationReturns401() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "empleado:read",
                          "resource": {"type": "empleado", "id": "emp-1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(401);
    }

    @Test
    void evaluateMissingActionReturns400() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "",
                          "resource": {"type": "empleado", "id": "emp-1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("error", equalTo("BAD_REQUEST"));
    }

    @Test
    void permissionsForAppRhWithAuth() {
        given().header("Authorization", AUTH)
                .queryParam("app", "rh")
                .when()
                .get("/v1/permissions")
                .then()
                .statusCode(200)
                .body("subject", equalTo("test-user"))
                .body("permissions", hasSize(greaterThan(0)))
                .body("policyVersion", notNullValue())
                .body("evaluatedAt", notNullValue());
    }

    @Test
    void permissionsWithoutAppReturns400() {
        given().header("Authorization", AUTH)
                .when()
                .get("/v1/permissions")
                .then()
                .statusCode(400)
                .body("error", equalTo("BAD_REQUEST"));
    }

    @Test
    void batchWithTwoRequestsReturnsTwoDecisions() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": [
                            {
                              "action": "empleado:read",
                              "resource": {"type": "empleado", "id": "emp-1"}
                            },
                            {
                              "action": "empleado:delete",
                              "resource": {"type": "empleado", "id": "emp-2"}
                            }
                          ]
                        }
                        """)
                .when()
                .post("/v1/evaluate/batch")
                .then()
                .statusCode(200)
                .body("decisions", hasSize(2));
    }

    @Test
    void evaluateMissingResourceReturns400() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "empleado:read"
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("error", equalTo("BAD_REQUEST"));
    }

    @Test
    void batchWithoutAuthorizationReturns401() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": [
                            {
                              "action": "empleado:read",
                              "resource": {"type": "empleado", "id": "emp-1"}
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
    void batchPreservesOrderOfPermitAndDenyDecisions() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": [
                            {
                              "action": "empleado:read",
                              "resource": {"type": "empleado", "id": "emp-1"}
                            },
                            {
                              "action": "empleado:delete",
                              "resource": {"type": "empleado", "id": "emp-1"}
                            }
                          ]
                        }
                        """)
                .when()
                .post("/v1/evaluate/batch")
                .then()
                .statusCode(200)
                .body("decisions[0].allowed", equalTo(true))
                .body("decisions[1].allowed", equalTo(false));
    }

    @Test
    void evaluateActionWithNoColonFallsBackToDefaultPermit() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "ping",
                          "resource": {"type": "system"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));
    }

    @Test
    void evaluateNoContextNoAttributesDefaultsToPermit() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "empleado:read",
                          "resource": {"type": "empleado", "id": "emp-1"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by default stub policy"));
    }

    @Test
    void permissionsForUnknownAppReturnsEmptyList() {
        given().header("Authorization", AUTH)
                .queryParam("app", "unknown-app")
                .when()
                .get("/v1/permissions")
                .then()
                .statusCode(200)
                .body("permissions", hasSize(0));
    }

    /**
     * Builds a minimal unsigned JWT with the given subject for use in tests.
     *
     * <p>Signature verification is intentionally skipped in Phase 1.5 (ADR-003).
     */
    private static String fakeToken(String sub) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
