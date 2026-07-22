package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for the action catalogue admin surface (ADR-028):
 * {@code /v1/apps/{app}/action-catalogue}. Covers the CRUD contract, the ETag / If-Match protocol
 * (ADR-018), the admin gate (ADR-013), the body-validation rejections, and per-app isolation
 * (ADR-026).
 *
 * <p>The in-use guard ({@code ACTION_IN_USE}) is exercised in {@code PolicyActionCatalogueTest},
 * where an active policy exists to do the blocking.
 */
@QuarkusTest
class ActionCatalogueResourceTest {

    private static final String ADMIN = "authz-admin";

    private static final String APP = "test-app";

    private static final String CATALOGUE = "/v1/apps/{app}/action-catalogue";

    private static final String ENTRY = "/v1/apps/{app}/action-catalogue/{resourceType}";

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        catalogueRepository.deleteAll();
    }

    // ── POST — create ────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createReturns201WithViewAndEtag() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": ["create", "read", "update"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(201)
                .header("ETag", equalTo("\"1\""))
                .body("app", equalTo(APP))
                .body("resourceType", equalTo("document"))
                .body("actions", contains("create", "read", "update"))
                .body("revision", equalTo(1));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createDuplicateResourceTypeReturns409() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": ["read", "delete"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(409)
                .body("code", equalTo("CATALOGUE_ENTRY_ALREADY_EXISTS"))
                .body("title", equalTo("Action catalogue entry already exists"));
    }

    // ── GET — list and single entry ──────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void listReturnsEveryEntrySortedByResourceType() {
        declare("request", "submit");
        declare("document", "read");

        given().when()
                .get(CATALOGUE, APP)
                .then()
                .statusCode(200)
                .body("entries", hasSize(2))
                .body("entries.resourceType", contains("document", "request"))
                .body("entries[0].app", equalTo(APP))
                .body("entries[0].actions", contains("read"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void listIsEmptyWhenTheAppDeclaresNothing() {
        given().when().get(CATALOGUE, APP).then().statusCode(200).body("entries", hasSize(0));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getEntryReturnsViewAndEtag() {
        declare("document", "read", "delete");

        given().when()
                .get(ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .header("ETag", equalTo("\"1\""))
                .body("app", equalTo(APP))
                .body("resourceType", equalTo("document"))
                .body("actions", contains("read", "delete"))
                .body("revision", equalTo(1));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getUnknownEntryReturns404() {
        given().when()
                .get(ENTRY, APP, "ghost")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("code", equalTo("CATALOGUE_ENTRY_NOT_FOUND"));
    }

    // ── PUT — replace ────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceReplacesTheWholeSetAndBumpsTheRevision() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read", "update", "delete"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .header("ETag", equalTo("\"2\""))
                .body("actions", contains("read", "update", "delete"))
                .body("revision", equalTo(2));

        given().when()
                .get(ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read", "update", "delete"))
                .body("revision", equalTo(2));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithoutIfMatchReturns428() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    /** An unparseable If-Match is refused like an absent one: never a blind write. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithUnparseableIfMatchReturns428() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"not-a-number\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithStaleIfMatchReturns412WithCurrentRevision() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"999\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("currentRevision", equalTo(1));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceUnknownEntryReturns404() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(ENTRY, APP, "ghost")
                .then()
                .statusCode(404)
                .body("code", equalTo("CATALOGUE_ENTRY_NOT_FOUND"));
    }

    /** A second write with the already-spent ETag loses: the lost-update guard (ADR-018). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void doubleReplaceWithTheSameIfMatchSecondGets412() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read", "update"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read", "delete"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"));
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteReturns204AndTheEntryIsGone() {
        declare("document", "read");

        given().header("If-Match", "\"1\"")
                .when()
                .delete(ENTRY, APP, "document")
                .then()
                .statusCode(204);

        given().when().get(ENTRY, APP, "document").then().statusCode(404);
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteWithoutIfMatchReturns428() {
        declare("document", "read");

        given().when()
                .delete(ENTRY, APP, "document")
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteWithStaleIfMatchReturns412() {
        declare("document", "read");

        given().header("If-Match", "\"999\"")
                .when()
                .delete(ENTRY, APP, "document")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("currentRevision", equalTo(1));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteUnknownEntryReturns404() {
        given().header("If-Match", "\"1\"")
                .when()
                .delete(ENTRY, APP, "ghost")
                .then()
                .statusCode(404)
                .body("code", equalTo("CATALOGUE_ENTRY_NOT_FOUND"));
    }

    // ── Admin gate ───────────────────────────────────────────────────────────

    @Test
    void unauthenticatedRequestReturns401() {
        given().when().get(CATALOGUE, APP).then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "plain-user")
    void listWithoutAdminMarkerReturns403() {
        given().when().get(CATALOGUE, APP).then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void createWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": ["read"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void getEntryWithoutAdminMarkerReturns403() {
        given().when().get(ENTRY, APP, "document").then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void replaceWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void deleteWithoutAdminMarkerReturns403() {
        given().header("If-Match", "\"1\"")
                .when()
                .delete(ENTRY, APP, "document")
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    // ── Body validation ──────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithNullBodyReturns400() {
        given().contentType(ContentType.JSON)
                .body("null")
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithoutResourceTypeReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithBlankResourceTypeReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "   ", "actions": ["read"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithoutActionsReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document"}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithEmptyActionsReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": []}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithBlankActionReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": ["read", "  "]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithDuplicateActionReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": ["read", "read"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** '*' is the sugar the catalogue defines away; it can never be a catalogue action itself. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithWildcardActionReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": ["*"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** ADR-026: the app is the path's; a body that also states it is rejected, not reconciled. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithAppInBodyReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"app": "test-app", "resourceType": "document", "actions": ["read"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"))
                .body("detail", equalTo("'app' must not be present in the body; it is determined by the path."));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithNullBodyReturns400() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("null")
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithEmptyActionsReturns400() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": []}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithWildcardActionReturns400() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["*"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithDuplicateActionReturns400() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read", "read"]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithBlankActionReturns400() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read", ""]}
                        """)
                .when()
                .put(ENTRY, APP, "document")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    // ── Per-app isolation ────────────────────────────────────────────────────

    /**
     * The catalogue is keyed by {@code (app, resourceType)}: the same resource type in two apps is
     * two entries, with independent action sets and independent revisions. Neither the unique index
     * nor a write in one app may reach the other.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void theSameResourceTypeInTwoAppsIsTwoIndependentEntries() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": ["read"]}
                        """)
                .when()
                .post(CATALOGUE, APP)
                .then()
                .statusCode(201);

        // Not a 409: another app declaring 'document' is a different entry.
        given().contentType(ContentType.JSON)
                .body("""
                        {"resourceType": "document", "actions": ["approve", "reject"]}
                        """)
                .when()
                .post(CATALOGUE, "other-app")
                .then()
                .statusCode(201)
                .body("app", equalTo("other-app"))
                .body("actions", contains("approve", "reject"));

        // Bumping one app's revision leaves the other's untouched.
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["approve"]}
                        """)
                .when()
                .put(ENTRY, "other-app", "document")
                .then()
                .statusCode(200)
                .body("revision", equalTo(2));

        given().when()
                .get(ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read"))
                .body("revision", equalTo(1));

        // And each listing shows only its own app.
        given().when()
                .get(CATALOGUE, "other-app")
                .then()
                .statusCode(200)
                .body("entries", hasSize(1))
                .body("entries[0].app", equalTo("other-app"));
    }

    /** An entry of another app is invisible here, exactly as a policy of another app is. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getEntryOfAnotherAppReturns404() {
        declare("document", "read");

        given().when()
                .get(ENTRY, "other-app", "document")
                .then()
                .statusCode(404)
                .body("code", equalTo("CATALOGUE_ENTRY_NOT_FOUND"))
                .body("type", notNullValue());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void declare(String resourceType, String... actions) {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, resourceType, actions);
    }
}
