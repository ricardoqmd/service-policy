package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for {@code SubjectResolver} subject extraction and error handling.
 *
 * <p>Exercises the JWT claim fallback chain ({@code sub} → {@code preferred_username} →
 * {@code "unknown"}) and the 401 error cases. Signature verification is intentionally skipped in
 * Phase 1.5 (ADR-003).
 */
@QuarkusTest
class SubjectResolverTest {

    private static final String EVALUATE_BODY = """
            {
              "action": "empleado:read",
              "resource": {"type": "empleado", "id": "emp-1"}
            }
            """;

    @Test
    void preferredUsernameFallbackWhenNoSub() {
        String token = buildToken("{\"alg\":\"none\"}", "{\"preferred_username\":\"alice\"}");

        given().header("Authorization", "Bearer " + token)
                .queryParam("app", "rh")
                .when()
                .get("/v1/permissions")
                .then()
                .statusCode(200)
                .body("subject", equalTo("alice"));
    }

    @Test
    void unknownSubjectWhenNeitherSubNorPreferredUsername() {
        String token = buildToken("{\"alg\":\"none\"}", "{\"iss\":\"test-realm\"}");

        given().header("Authorization", "Bearer " + token)
                .queryParam("app", "rh")
                .when()
                .get("/v1/permissions")
                .then()
                .statusCode(200)
                .body("subject", equalTo("unknown"));
    }

    @Test
    void nonBearerAuthorizationHeaderReturns401() {
        given().header("Authorization", "Basic dXNlcjpwYXNz")
                .contentType(ContentType.JSON)
                .body(EVALUATE_BODY)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(401);
    }

    @Test
    void tokenWithOneSegmentReturns401() {
        given().header("Authorization", "Bearer onlyone")
                .contentType(ContentType.JSON)
                .body(EVALUATE_BODY)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(401);
    }

    @Test
    void invalidBase64PayloadReturns401() {
        // payload segment contains characters that are not valid base64url
        given().header("Authorization", "Bearer eyJhbGciOiJub25lIn0.!!!invalid!!!.")
                .contentType(ContentType.JSON)
                .body(EVALUATE_BODY)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(401);
    }

    private static String buildToken(String header, String payload) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String h = enc.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String p = enc.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return h + "." + p + ".";
    }
}
