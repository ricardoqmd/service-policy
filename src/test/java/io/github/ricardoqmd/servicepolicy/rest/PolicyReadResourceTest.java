package io.github.ricardoqmd.servicepolicy.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadDocument;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionDocument;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * HTTP tests for the policy read endpoints (ADR-016 / ADR-017): the collection envelope, lean vs
 * {@code ?view=full} projection, single-resource shapes, and the 403/404/400 guards. Every route is
 * nested under its application (ADR-026), so the app is a path coordinate rather than a body or
 * query field. Uses {@code @TestSecurity} with the {@code authz-admin} role to satisfy the admin
 * marker (ADR-013); a request with an authenticated-but-unprivileged identity verifies the 403
 * guard.
 */
@QuarkusTest
class PolicyReadResourceTest {

    private static final String ADMIN = "authz-admin";

    private static final String APP = "test-app";

    private static final String POLICIES = "/v1/apps/" + APP + "/policies";

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    private final PolicyDocumentMapper contentMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

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
            roles = {ADMIN})
    void listReturnsLeanEnvelopeByDefault() {
        seedHead("p-a", 1, 1, policy("p-a", 1));

        given().when()
                .get(POLICIES)
                .then()
                .statusCode(200)
                .body("data.size()", equalTo(1))
                .body("data[0].policyId", equalTo("p-a"))
                .body("data[0].app", equalTo(APP))
                .body("data[0].activeVersion", equalTo(1))
                .body("data[0].activeContent", nullValue()) // lean: no content
                .body("pagination.page", equalTo(1))
                .body("pagination.size", equalTo(20))
                .body("pagination.totalElements", equalTo(1))
                .body("pagination.totalPages", equalTo(1));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void listEmbedsContentWhenViewFull() {
        seedHead("p-a", 1, 1, policy("p-a", 1));

        given().queryParam("view", "full")
                .when()
                .get(POLICIES)
                .then()
                .statusCode(200)
                .body("data[0].activeContent.policyId", equalTo("p-a"))
                .body("data[0].activeContent.combiningAlgorithm", equalTo("DENY_OVERRIDES"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getByIdReturnsFullHead() {
        seedHead("p-a", 1, 7, policy("p-a", 1));

        given().when()
                .get(POLICIES + "/p-a")
                .then()
                .statusCode(200)
                .body("policyId", equalTo("p-a"))
                .body("app", equalTo(APP))
                .body("revision", equalTo(7))
                .body("activeContent", notNullValue())
                .body("audit.createdBy", equalTo("u-1"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getByIdIsNotFoundForUnknownPolicy() {
        given().when().get(POLICIES + "/nope").then().statusCode(404).body("code", equalTo("POLICY_NOT_FOUND"));
    }

    /**
     * A policy is identified by {@code (app, policyId)} (ADR-026): the very same id, seeded in
     * another app, is not visible through this app's route.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getByIdIsNotFoundForAPolicyOfAnotherApp() {
        seedHead("p-a", 1, 1, policy("p-a", 1));

        given().when()
                .get("/v1/apps/other-app/policies/p-a")
                .then()
                .statusCode(404)
                .body("code", equalTo("POLICY_NOT_FOUND"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void listVersionsReturnsNewestFirst() {
        seedHead("p-a", 2, 2, policy("p-a", 2));
        seedVersion("p-a", 1);
        seedVersion("p-a", 2);

        given().when()
                .get(POLICIES + "/p-a/versions")
                .then()
                .statusCode(200)
                .body("data.size()", equalTo(2))
                .body("data[0].version", equalTo(2))
                .body("data[0].app", equalTo(APP))
                .body("data[1].version", equalTo(1))
                .body("pagination.totalElements", equalTo(2));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void listVersionsIsNotFoundWhenPolicyUnknown() {
        given().when()
                .get(POLICIES + "/nope/versions")
                .then()
                .statusCode(404)
                .body("code", equalTo("POLICY_NOT_FOUND"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getVersionReturnsBareContent() {
        seedHead("p-a", 1, 1, policy("p-a", 1));
        seedVersion("p-a", 1);

        given().when()
                .get(POLICIES + "/p-a/versions/1")
                .then()
                .statusCode(200)
                .body("policyId", equalTo("p-a")) // bare content, no envelope
                .body("version", equalTo(1))
                .body("resourceType", equalTo("document"))
                .body("app", nullValue()); // the app is a coordinate, never part of the content
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getVersionIsNotFoundWhenMissing() {
        seedHead("p-a", 1, 1, policy("p-a", 1));
        seedVersion("p-a", 1);

        given().when()
                .get(POLICIES + "/p-a/versions/99")
                .then()
                .statusCode(404)
                .body("code", equalTo("VERSION_NOT_FOUND"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void rejectsInvalidPaging() {
        given().queryParam("size", 999)
                .when()
                .get(POLICIES)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void rejectsCallerWithoutAdminMarker() {
        given().when().get(POLICIES).then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    // --- seeding helpers -----------------------------------------------------

    private Policy policy(String id, int version) {
        return new Policy(
                id,
                version,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "assigned-access",
                        Effect.PERMIT,
                        new Comparison(
                                Operator.IN,
                                new AttributeRef("subject.id"),
                                new AttributeRef("resource.attr.assignees")))));
    }

    private Document audit(String changeReason) {
        return new Document("createdBy", "u-1")
                .append("createdAt", "2026-07-01T00:00:00Z")
                .append("changeReason", changeReason);
    }

    private void seedHead(String policyId, Integer activeVersion, long revision, Policy activeContent) {
        PolicyHeadDocument doc = new PolicyHeadDocument();
        doc.policyId = policyId;
        doc.app = APP;
        doc.resourceType = "document";
        doc.activeVersion = activeVersion;
        doc.revision = revision;
        doc.audit = audit("seed");
        doc.activeContent = activeContent == null ? null : new Document(contentMapper.toDocument(activeContent));
        headRepository.persist(doc);
    }

    private void seedVersion(String policyId, int version) {
        PolicyVersionDocument doc = new PolicyVersionDocument();
        doc.policyId = policyId;
        doc.app = APP; // part of the version's identity (ADR-026)
        doc.version = version;
        doc.content = new Document(contentMapper.toDocument(policy(policyId, version)));
        doc.audit = audit("v" + version);
        versionRepository.persist(doc);
    }
}
