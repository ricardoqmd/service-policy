package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
 * Integration tests for the activation write-path: POST /{id}/activate and POST /{id}/deactivate
 * (ADR-020). Covers the conditional-write guards (ADR-018), 404 disambiguation, the ETag contract,
 * and the no-admin 403 guard.
 */
@QuarkusTest
class PolicyActivationResourceTest {

    private static final String ADMIN = "authz-admin";

    private static final String VALID_POLICY = """
            {
              "app": "test-app",
              "policyId": "p-act",
              "version": 1,
              "resourceType": "document",
              "actions": ["read"],
              "combiningAlgorithm": "DENY_OVERRIDES",
              "defaultEffect": "DENY",
              "rules": [
                {
                  "id": "r1", "effect": "PERMIT",
                  "condition": {"type": "comparison", "op": "EQ",
                    "left": {"ref": "subject.id"}, "right": {"value": "admin"}}
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

    // ── POST /{id}/activate — happy path ─────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void activateVersionReturns200WithEtagAndActiveContent() {
        createPolicy();
        String etag = headEtag();

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(200)
                .header("ETag", notNullValue())
                .body("policyId", equalTo("p-act"))
                .body("activeVersion", equalTo(1))
                .body("activeContent", notNullValue());
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void activateBumpsRevisionAndChangesEtag() {
        createPolicy();
        String etagBefore = headEtag();

        String etagAfter = given().contentType(ContentType.JSON)
                .header("If-Match", etagBefore)
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        assertNotEquals(etagBefore, etagAfter);
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void reActivateSameVersionBumpsRevisionAgain() {
        createPolicy();
        String etag1 = headEtag();

        String etag2 = given().contentType(ContentType.JSON)
                .header("If-Match", etag1)
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        String etag3 = given().contentType(ContentType.JSON)
                .header("If-Match", etag2)
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        assertNotEquals(etag2, etag3);
    }

    // ── POST /{id}/activate — guards ─────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void activateWithoutIfMatchReturns428() {
        createPolicy();

        given().contentType(ContentType.JSON)
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void activateWithStaleIfMatchReturns412WithCurrentRevision() {
        createPolicy();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"999\"")
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("policyId", equalTo("p-act"))
                .body("currentRevision", notNullValue());
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void activateUnknownPolicyReturns404() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/ghost/activate")
                .then()
                .statusCode(404)
                .body("code", equalTo("POLICY_NOT_FOUND"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void activateUnknownVersionReturns404VersionNotFound() {
        createPolicy();
        String etag = headEtag();

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("""
                        {"version": 99}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(404)
                .body("code", equalTo("VERSION_NOT_FOUND"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void activateWithMissingVersionFieldInBodyReturns400() {
        createPolicy();
        String etag = headEtag();

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("""
                        {"changeReason": "no version field"}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void activateWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    // ── POST /{id}/deactivate — happy path ───────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deactivateActiveVersionReturns200WithNullActiveVersion() {
        createPolicy();
        String etag1 = headEtag();

        String etag2 = given().contentType(ContentType.JSON)
                .header("If-Match", etag1)
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag2)
                .when()
                .post("/v1/policies/p-act/deactivate")
                .then()
                .statusCode(200)
                .header("ETag", notNullValue())
                .body("policyId", equalTo("p-act"))
                .body("activeVersion", nullValue())
                .body("activeContent", nullValue());
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deactivateWithOptionalBodyWorks() {
        createPolicy();
        String etag1 = headEtag();

        String etag2 = given().contentType(ContentType.JSON)
                .header("If-Match", etag1)
                .body("""
                        {"version": 1}
                        """)
                .when()
                .post("/v1/policies/p-act/activate")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag2)
                .body("""
                        {"changeReason": "retiring this version"}
                        """)
                .when()
                .post("/v1/policies/p-act/deactivate")
                .then()
                .statusCode(200)
                .body("activeVersion", nullValue());
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deactivateAlreadyInactivePolicyBumpsRevision() {
        createPolicy();
        String etag = headEtag();

        String etag2 = given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .when()
                .post("/v1/policies/p-act/deactivate")
                .then()
                .statusCode(200)
                .body("activeVersion", nullValue())
                .extract()
                .header("ETag");

        assertNotEquals(etag, etag2);
    }

    // ── POST /{id}/deactivate — guards ───────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deactivateWithoutIfMatchReturns428() {
        createPolicy();

        given().contentType(ContentType.JSON)
                .when()
                .post("/v1/policies/p-act/deactivate")
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deactivateWithStaleIfMatchReturns412() {
        createPolicy();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"999\"")
                .when()
                .post("/v1/policies/p-act/deactivate")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("policyId", equalTo("p-act"))
                .body("currentRevision", notNullValue());
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deactivateUnknownPolicyReturns404() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .when()
                .post("/v1/policies/ghost/deactivate")
                .then()
                .statusCode(404)
                .body("code", equalTo("POLICY_NOT_FOUND"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void deactivateWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .when()
                .post("/v1/policies/p-act/deactivate")
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void createPolicy() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);
    }

    private String headEtag() {
        return given().when()
                .get("/v1/policies/p-act")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");
    }
}
