package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.model.Filters;

import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * On a control-plane write, a declaration of "on behalf of" is recorded and authorizes nothing (ADR-033 §4).
 *
 * <p>The audit of every such write carries three facts together: {@code createdBy}, the calling credential;
 * {@code subject}, who the caller says acted; and {@code subjectProvenance}, {@code VERIFIED} when that
 * identity is the validated token's own and {@code DECLARED} when the caller asserted it.
 */
@QuarkusTest
class DeclaredSubjectAuditTest {

    @Inject
    ControlPlaneFixtures fixtures;

    @Inject
    PolicyHeadRepository headRepository;

    @BeforeEach
    void arrange() {
        fixtures.arrange();
    }

    @AfterEach
    void cleanUp() {
        fixtures.wipe();
    }

    // ── What is recorded ─────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "console-a"),
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY)
            })
    void aWriteWithoutADeclarationRecordsTheCallerAsVerified() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body("{\"version\": 1}")
                .when()
                .post("/v1/apps/{app}/policies/p-1/activate", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200)
                .body("audit.createdBy", equalTo("console-a"))
                .body("audit.subject", equalTo("console-a"))
                .body("audit.subjectProvenance", equalTo("VERIFIED"));

        assertAudit("app_configs", configOf(ControlPlaneFixtures.MINE_2, null), "console-a", "console-a", "VERIFIED");
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "console-a"),
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY)
            })
    void aWriteWithADeclarationRecordsTheDeclaredSubjectTheCredentialAndDeclared() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body("{\"version\": 1, \"subject\": \"person-17\"}")
                .when()
                .post("/v1/apps/{app}/policies/p-1/activate", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200)
                .body("audit.createdBy", equalTo("console-a"))
                .body("audit.subject", equalTo("person-17"))
                .body("audit.subjectProvenance", equalTo("DECLARED"));

        assertAudit(
                "app_configs",
                configOf(ControlPlaneFixtures.MINE_2, "person-17"),
                "console-a",
                "person-17",
                "DECLARED");
    }

    /** Declaring the caller itself is no declaration: the identity is the token's, so it is verified. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "console-a"),
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY)
            })
    void declaringTheCallerItselfIsVerified() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body("{\"subject\": \"console-a\"}")
                .when()
                .post("/v1/apps/{app}/policies/p-1/deactivate", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200)
                .body("audit.subject", equalTo("console-a"))
                .body("audit.subjectProvenance", equalTo("VERIFIED"));
    }

    /** Every write that takes a body accepts the declaration and records it. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "console-a"),
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY)
            })
    void everyWriteWithABodyRecordsItsDeclaration() {
        String policy =
                ControlPlaneFixtures.POLICY_TEMPLATE.formatted("p-declared").strip();
        given().contentType(ContentType.JSON)
                .body(policy.substring(0, policy.length() - 1) + ", \"subject\": \"person-1\"}")
                .when()
                .post("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(201);
        assertAudit("policy_heads", Filters.eq("policyId", "p-declared"), "console-a", "person-1", "DECLARED");
        assertAudit("policy_versions", Filters.eq("policyId", "p-declared"), "console-a", "person-1", "DECLARED");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body("{\"content\": " + ControlPlaneFixtures.POLICY_TEMPLATE.formatted("p-1")
                        + ", \"subject\": \"person-2\"}")
                .when()
                .put("/v1/apps/{app}/policies/p-1", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
        assertAudit(
                "policy_versions",
                Filters.and(Filters.eq("policyId", "p-1"), Filters.eq("version", 2)),
                "console-a",
                "person-2",
                "DECLARED");

        given().contentType(ContentType.JSON)
                .body("{\"resourceType\": \"invoice\", \"actions\": [\"read\"], \"subject\": \"person-3\"}")
                .when()
                .post("/v1/apps/{app}/action-catalogue", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(201);
        assertAudit("action_catalogue", Filters.eq("resourceType", "invoice"), "console-a", "person-3", "DECLARED");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("{\"actions\": [\"read\", \"approve\"], \"subject\": \"person-4\"}")
                .when()
                .put("/v1/apps/{app}/action-catalogue/invoice", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
        assertAudit("action_catalogue", Filters.eq("resourceType", "invoice"), "console-a", "person-4", "DECLARED");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("{\"subjectAttributes\": {\"unit\": \"other-unit\"}, \"subject\": \"person-5\"}")
                .when()
                .put("/v1/apps/{app}/configuration", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
        assertAudit("app_configs", Filters.eq("app", ControlPlaneFixtures.MINE), "console-a", "person-5", "DECLARED");
    }

    // ── What it does not do ──────────────────────────────────────────────────────

    /**
     * The same write, with and without a declaration, for a caller that may and a caller that may not: the
     * declaration changes the outcome in neither case. The refusals are held to the literal denial.
     */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "console-a"),
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY)
            })
    void aDeclarationNeverChangesWhetherTheWriteIsPermitted() {
        assertEquals(
                201, createCatalogue(ControlPlaneFixtures.MINE, "invoice", null).statusCode());
        assertEquals(
                201,
                createCatalogue(ControlPlaneFixtures.MINE, "receipt", "person-9")
                        .statusCode());

        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(createCatalogue(ControlPlaneFixtures.OTHER, "invoice", null)));
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(createCatalogue(ControlPlaneFixtures.OTHER, "invoice", "person-9")));
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(createCatalogue(ControlPlaneFixtures.OTHER, "invoice", "console-a")));
    }

    private static Response createCatalogue(String app, String resourceType, String subject) {
        String declaration = subject == null ? "" : ", \"subject\": \"" + subject + "\"";
        return given().contentType(ContentType.JSON)
                .body("{\"resourceType\": \"" + resourceType + "\", \"actions\": [\"read\"]" + declaration + "}")
                .when()
                .post("/v1/apps/{app}/action-catalogue", app);
    }

    private static org.bson.conversions.Bson configOf(String app, String subject) {
        String declaration = subject == null ? "" : ", \"subject\": \"" + subject + "\"";
        given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"unit\": \"unit\"}" + declaration + "}")
                .when()
                .post("/v1/apps/{app}/configuration", app)
                .then()
                .statusCode(201);
        return Filters.eq("app", app);
    }

    private void assertAudit(
            String collection, org.bson.conversions.Bson filter, String createdBy, String subject, String provenance) {
        Document stored = headRepository
                .mongoDatabase()
                .getCollection(collection)
                .find(filter)
                .sort(new Document("_id", -1))
                .first();
        Document audit = stored.get("audit", Document.class);
        assertEquals(createdBy, audit.getString("createdBy"), collection + " createdBy");
        assertEquals(subject, audit.getString("subject"), collection + " subject");
        assertEquals(provenance, audit.getString("subjectProvenance"), collection + " subjectProvenance");
    }
}
