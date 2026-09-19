package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfig;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigRepository;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * The reserved control-plane application (ADR-033 §6): where the rules are kept, never what they decide
 * about; not creatable or deletable as an ordinary application; and its claim mapping replaceable only in a
 * way its own author can undo.
 */
@QuarkusTest
class ReservedApplicationTest {

    private static final String RESERVED = ControlPlaneTestSupport.RESERVED_APP;
    private static final String CONFIGURATION = "/v1/apps/{app}/configuration";

    /** The refusal to create or delete the reserved application's configuration, as a client receives it. */
    static final String RESERVED_ON_THE_WIRE = """
            HTTP/1.1 403 Forbidden
            content-length: 243
            content-type: application/problem+json

            {"type":"https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#forbidden",\
            "code":"FORBIDDEN","title":"Forbidden","status":403,\
            "detail":"the configuration of the reserved control-plane application cannot be created or deleted."}""";

    @Inject
    ControlPlaneFixtures fixtures;

    @Inject
    AppConfigStore configStore;

    @Inject
    AppConfigRepository configRepository;

    @Inject
    InstallationStore installationStore;

    @BeforeEach
    void arrange() {
        fixtures.arrange();
    }

    @AfterEach
    void cleanUp() {
        fixtures.wipe();
    }

    // ── Not an ordinary application ──────────────────────────────────────────────

    /** A caller that holds the reserved application: the refusal is not the gate's, and it is not a 409. */
    @Test
    @TestSecurity(user = "operator")
    @OidcSecurity(
            claims = @Claim(key = "apps", value = "[\"service-policy-control-plane\"]", type = ClaimType.JSON_ARRAY))
    void theReservedIdentifierIsRefusedAtCreation() {
        assertEquals(RESERVED_ON_THE_WIRE, ControlPlaneFixtures.wire(createReserved()));
        assertEquals(1, configStore.find(RESERVED).orElseThrow().revision());
    }

    /**
     * With no configuration to refuse over: before installation, with the reserved configuration gone, the
     * bootstrap subject — whom the gate permits — still cannot create it, and installation stays open.
     */
    @Test
    @TestSecurity(user = ControlPlaneTestSupport.BOOTSTRAP_SUBJECT)
    @OidcSecurity(claims = @Claim(key = "sub", value = ControlPlaneTestSupport.BOOTSTRAP_SUBJECT))
    void theReservedIdentifierIsRefusedAtCreationWhenItHasNoConfiguration() {
        fixtures.controlPlane.notInstalled();
        configRepository.delete("app", RESERVED);
        fixtures.configProvider.invalidate(RESERVED);

        assertEquals(RESERVED_ON_THE_WIRE, ControlPlaneFixtures.wire(createReserved()));
        assertTrue(configStore.find(RESERVED).isEmpty());
        assertFalse(installationStore.marker().installed());
    }

    /** A caller that does hold the reserved application, so the refusal is not the gate's. */
    @Test
    @TestSecurity(user = "operator")
    @OidcSecurity(
            claims = @Claim(key = "apps", value = "[\"service-policy-control-plane\"]", type = ClaimType.JSON_ARRAY))
    void theReservedConfigurationCannotBeDeletedByACallerThatHoldsIt() {
        given().when().get(CONFIGURATION, RESERVED).then().statusCode(200);

        Response delete = given().header("If-Match", "\"1\"").when().delete(CONFIGURATION, RESERVED);

        assertEquals(RESERVED_ON_THE_WIRE, ControlPlaneFixtures.wire(delete));
        assertEquals(1, configStore.find(RESERVED).orElseThrow().revision());
    }

    /** Without the reserved application, the ordinary denial: the identifier cannot be probed for. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void aCallerWithoutTheReservedApplicationGetsTheOrdinaryDenial() {
        assertEquals(ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE, ControlPlaneFixtures.wire(createReserved()));
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(
                        given().header("If-Match", "\"1\"").when().delete(CONFIGURATION, RESERVED)));
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(replaceReserved("{\"apps\": \"apps\"}", 1)));
    }

    /**
     * A policy kept in the reserved application decides about an application named in the route. The grant is
     * a read-only one for a caller that does not hold the application: the set is restructured by verb — the
     * baseline narrowed to the write verbs, and a read policy that also names the operator — because under
     * deny-overrides every policy selected for a verb must permit it.
     */
    @Test
    @TestSecurity(user = "operator")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "operator"),
                @Claim(key = "apps", value = "[\"service-policy-control-plane\"]", type = ClaimType.JSON_ARRAY)
            })
    void aPolicyStoredInTheReservedApplicationDecidesAboutAnotherApplication() {
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(403);

        String writesByApps = """
                {"policyId": "control-plane-baseline", "version": 2, "resourceType": "policy",
                 "actions": ["write", "activate", "deactivate"],
                 "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                 "rules": [{"id": "app-in-subject-apps", "effect": "PERMIT", "condition": {"type": "comparison",
                   "op": "IN", "left": {"ref": "resource.attr.app"}, "right": {"ref": "subject.attr.apps"}}}]}
                """;
        String readsByAppsOrOperator = """
                {"policyId": "operator-reads-everything", "version": 1, "resourceType": "policy", "actions": ["read"],
                 "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                 "rules": [
                   {"id": "app-in-subject-apps", "effect": "PERMIT", "condition": {"type": "comparison", "op": "IN",
                     "left": {"ref": "resource.attr.app"}, "right": {"ref": "subject.attr.apps"}}},
                   {"id": "operator", "effect": "PERMIT", "condition": {"type": "comparison", "op": "EQ",
                     "left": {"ref": "subject.id"}, "right": {"value": "operator"}}}]}
                """;
        given().contentType(ContentType.JSON)
                .body(readsByAppsOrOperator)
                .when()
                .post("/v1/apps/{app}/policies", RESERVED)
                .then()
                .statusCode(201);
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body("{\"version\": 1}")
                .when()
                .post("/v1/apps/{app}/policies/operator-reads-everything/activate", RESERVED)
                .then()
                .statusCode(200);
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("{\"content\": " + writesByApps + "}")
                .when()
                .put("/v1/apps/{app}/policies/control-plane-baseline", RESERVED)
                .then()
                .statusCode(200);
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"2\"")
                .body("{\"version\": 2}")
                .when()
                .post("/v1/apps/{app}/policies/control-plane-baseline/activate", RESERVED)
                .then()
                .statusCode(200);

        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(200);
        given().contentType(ContentType.JSON)
                .body("{\"resourceType\": \"invoice\", \"actions\": [\"read\"]}")
                .when()
                .post("/v1/apps/{app}/action-catalogue", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(403);
    }

    // ── The self-lockout guard ───────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "operator")
    @OidcSecurity(
            claims = {
                @Claim(key = "apps", value = "[\"service-policy-control-plane\"]", type = ClaimType.JSON_ARRAY),
                @Claim(key = "admin_apps", value = "[\"service-policy-control-plane\"]", type = ClaimType.JSON_ARRAY)
            })
    void aChangeThatKeepsTheCallerInIsAccepted() {
        replaceReserved("{\"apps\": \"admin_apps\", \"unit\": \"unit\"}", 1)
                .then()
                .statusCode(200)
                .body("subjectAttributes.apps", equalTo("admin_apps"));

        given().when().get(CONFIGURATION, RESERVED).then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "operator")
    @OidcSecurity(
            claims = {
                @Claim(key = "apps", value = "[\"service-policy-control-plane\"]", type = ClaimType.JSON_ARRAY),
                @Claim(key = "other_apps", value = "[\"app-other\"]", type = ClaimType.JSON_ARRAY)
            })
    void aChangeThatWouldDropTheCallerIsRefusedAndStoresNothing() {
        AppConfig before = configStore.find(RESERVED).orElseThrow();

        replaceReserved("{\"apps\": \"other_apps\"}", 1)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams[0].field", equalTo("subjectAttributes.apps"));
        replaceReserved("{\"unit\": \"unit\"}", 1).then().statusCode(400).body("code", equalTo("INVALID_APP_CONFIG"));

        AppConfig after = configStore.find(RESERVED).orElseThrow();
        assertEquals(before, after);
        assertEquals(Map.of("apps", "apps"), after.subjectAttributes());
        given().when().get(CONFIGURATION, RESERVED).then().statusCode(200);
    }

    /** The guard is precondition-ordered like every other validation: a stale If-Match is 412 first. */
    @Test
    @TestSecurity(user = "operator")
    @OidcSecurity(
            claims = @Claim(key = "apps", value = "[\"service-policy-control-plane\"]", type = ClaimType.JSON_ARRAY))
    void aStaleIfMatchIsAnswered412BeforeTheGuard() {
        replaceReserved("{\"apps\": \"nothing\"}", 9).then().statusCode(412);
    }

    private static Response createReserved() {
        return given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"apps\": \"apps\"}}")
                .when()
                .post(CONFIGURATION, RESERVED);
    }

    private static Response replaceReserved(String mapping, long ifMatch) {
        return given().contentType(ContentType.JSON)
                .header("If-Match", "\"" + ifMatch + "\"")
                .body("{\"subjectAttributes\": " + mapping + "}")
                .when()
                .put(CONFIGURATION, RESERVED);
    }
}
