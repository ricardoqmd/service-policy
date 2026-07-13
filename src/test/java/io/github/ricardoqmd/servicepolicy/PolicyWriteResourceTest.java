package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadDocument;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for the write endpoints (POST create, PUT append) and the RFC 9457 error
 * surface (ADR-018, ADR-019).
 */
@QuarkusTest
class PolicyWriteResourceTest {

    private static final String ADMIN = "authz-admin";

    private static final String VALID_POLICY = """
            {
              "app": "test-app",
              "policyId": "p-write",
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

    private static final String VALID_CONTENT_BODY = """
            {
              "content": {
                "app": "test-app",
                "policyId": "p-write",
                "version": 2,
                "resourceType": "document",
                "actions": ["read"],
                "combiningAlgorithm": "DENY_OVERRIDES",
                "defaultEffect": "DENY",
                "rules": [
                  {
                    "id": "r2", "effect": "DENY",
                    "condition": {"type": "comparison", "op": "EQ",
                      "left": {"ref": "subject.id"}, "right": {"value": "blocked"}}
                  }
                ]
              }
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

    // ── POST create ──────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createReturnsInactivePolicy() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201)
                .body("policyId", equalTo("p-write"))
                .body("version", equalTo(1))
                .body("active", equalTo(false));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createDuplicateReturns409() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(409)
                .body("code", equalTo("POLICY_ALREADY_EXISTS"))
                .body("policyId", equalTo("p-write"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createSelfHealsOrphanHead() {
        PolicyHeadDocument orphan = new PolicyHeadDocument();
        orphan.policyId = "p-write";
        orphan.app = "test-app";
        orphan.resourceType = "document";
        orphan.activeVersion = null;
        orphan.activeContent = null;
        orphan.revision = 0L;
        orphan.audit = new Document("createdBy", "seed")
                .append("createdAt", "2026-01-01T00:00:00Z")
                .append("changeReason", "orphan");
        headRepository.persist(orphan);

        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201)
                .body("policyId", equalTo("p-write"))
                .body("active", equalTo(false));
    }

    // ── GET ETag ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getByIdEmitsEtag() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        given().when()
                .get("/v1/policies/p-write")
                .then()
                .statusCode(200)
                .header("ETag", notNullValue())
                .body("revision", equalTo(0));
    }

    // ── PUT append ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void putAppendsVersionAndBumpsRevision() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        String etag = given().when()
                .get("/v1/policies/p-write")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body(VALID_CONTENT_BODY)
                .when()
                .put("/v1/policies/p-write")
                .then()
                .statusCode(200)
                .body("policyId", equalTo("p-write"))
                .body("version", equalTo(2))
                .body("active", equalTo(false));

        given().when().get("/v1/policies/p-write").then().statusCode(200).body("revision", equalTo(1));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void putWithoutIfMatchReturns428() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body(VALID_CONTENT_BODY)
                .when()
                .put("/v1/policies/p-write")
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void putWithStaleIfMatchReturns412() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"999\"")
                .body(VALID_CONTENT_BODY)
                .when()
                .put("/v1/policies/p-write")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("policyId", equalTo("p-write"))
                .body("currentRevision", notNullValue());
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void putOnNonExistentPolicyReturns404() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body(VALID_CONTENT_BODY)
                .when()
                .put("/v1/policies/ghost")
                .then()
                .statusCode(404)
                .body("code", equalTo("POLICY_NOT_FOUND"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void putWithInvalidBodyReturns400() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        String etag = given().when()
                .get("/v1/policies/p-write")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("""
                        {
                          "content": {
                            "app": "test-app",
                            "policyId": "p-write",
                            "version": 2,
                            "resourceType": "document",
                            "actions": ["read"],
                            "combiningAlgorithm": "DENY_OVERRIDES",
                            "defaultEffect": "DENY",
                            "rules": [
                              {"id": "r1", "effect": "PERMIT",
                               "condition": {"type": "comparison", "op": "NOPE",
                                 "left": {"ref": "subject.id"}, "right": {"value": "x"}}}
                            ]
                          }
                        }
                        """)
                .when()
                .put("/v1/policies/p-write")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams", notNullValue());
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void doubleAppendWithSameIfMatchSecondGets412() {
        given().contentType(ContentType.JSON)
                .body(VALID_POLICY)
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);

        String etag = given().when()
                .get("/v1/policies/p-write")
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body(VALID_CONTENT_BODY)
                .when()
                .put("/v1/policies/p-write")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body(VALID_CONTENT_BODY)
                .when()
                .put("/v1/policies/p-write")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"));
    }

    // ── problem+json content-type ─────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void errorResponsesCarryProblemJsonContentType() {
        given().when()
                .get("/v1/policies/ghost")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("code", equalTo("POLICY_NOT_FOUND"));
    }
}
