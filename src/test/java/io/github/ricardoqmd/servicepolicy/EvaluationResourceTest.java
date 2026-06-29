package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for the PEP-facing evaluation and permissions endpoints against the
 * persistence-backed evaluator. No policies are seeded here, so every evaluation falls through to
 * the fail-safe default deny — this increment proves the wiring end to end. Seeded permit/deny
 * scenarios over the neutral domain land in the next increment (2b-2).
 *
 * <p>Uses a fake unsigned JWT for the {@code Authorization} header since signature verification is
 * deferred to Phase 3 (ADR-003).
 */
@QuarkusTest
class EvaluationResourceTest {

    private static final String AUTH = "Bearer " + fakeToken("test-user");

    @Test
    void evaluateWithNoApplicablePolicyDefaultsToDeny() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
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
    void evaluateAcceptsSubjectAttributesWithoutError() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1", "attributes": {"area": "A"}},
                          "subjectAttributes": {"area": "A"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false)); // no policy seeded yet -> default deny
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
    void evaluateMissingActionReturns400() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
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
                .body("error", equalTo("BAD_REQUEST"));
    }

    @Test
    void evaluateMissingResourceReturns400() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read"
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(400)
                .body("error", equalTo("BAD_REQUEST"));
    }

    @Test
    void batchReturnsADecisionPerRequestAndGuardsBlankResource() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
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
                .post("/v1/evaluate/batch")
                .then()
                .statusCode(200)
                .body("decisions", hasSize(2))
                .body("decisions[0].allowed", equalTo(false))
                .body("decisions[1].allowed", equalTo(false))
                .body("decisions[1].reason", equalTo("resource.type must not be blank"));
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
    void permissionsReturnsEmptyListForNow() {
        given().header("Authorization", AUTH)
                .queryParam("app", "rh")
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
    void permissionsWithoutAppReturns400() {
        given().header("Authorization", AUTH)
                .when()
                .get("/v1/permissions")
                .then()
                .statusCode(400)
                .body("error", equalTo("BAD_REQUEST"));
    }

    /**
     * Builds a minimal unsigned JWT with the given subject for use in tests.
     *
     * <p>Signature verification is intentionally skipped until Phase 3 (ADR-003).
     */
    private static String fakeToken(String sub) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
