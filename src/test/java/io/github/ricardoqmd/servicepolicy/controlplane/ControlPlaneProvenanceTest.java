package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.rest.ControlPlaneGate;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/**
 * Where the subject attributes of a control-plane decision come from (ADR-033 §3): the caller's validated
 * token, through the mapping stored for the reserved control-plane application — never the request body,
 * and never the configuration of the application being decided about.
 *
 * <p>Each case asserts the decision, not merely that a request was rejected: a body that is accepted and
 * ignored is shown to change nothing, by making the decision go the way the token says while the body says
 * the opposite.
 */
@QuarkusTest
class ControlPlaneProvenanceTest {

    @Inject
    ControlPlaneFixtures fixtures;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    AppConfigStore configStore;

    @BeforeEach
    void arrange() {
        fixtures.arrange();
    }

    @AfterEach
    void cleanUp() {
        fixtures.wipe();
    }

    // ── The type: there is nothing to pass ─────────────────────────────────────

    /**
     * A caller-supplied attribute cannot reach a control-plane decision because no entry point has a parameter
     * that could carry one: the route's application, the action, and the validated identity — nothing else.
     */
    @Test
    void noControlPlaneDecisionEntryPointAcceptsSubjectAttributes() {
        assertEquals(
                List.of(String.class, ControlPlaneAction.class, String.class, JsonWebToken.class),
                parameters(ControlPlaneAuthorizer.class, "decide"));
        assertEquals(
                List.of(String.class, JsonWebToken.class), parameters(ControlPlaneAuthorizer.class, "readableApps"));
        assertEquals(List.of(String.class, ControlPlaneAction.class), parameters(ControlPlaneGate.class, "authorize"));
        assertEquals(List.of(), parameters(ControlPlaneGate.class, "readableApps"));
    }

    // ── The request body ─────────────────────────────────────────────────────────

    /** A policy body is an untyped document, so a stray bag is accepted by the parser; it still decides nothing. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void subjectAttributesInAPolicyBodyNeitherGrantNorWithdrawAccess() {
        String grantingOther = policyWithBag("p-body", "[\"app-other\"]");
        given().contentType(ContentType.JSON)
                .body(grantingOther)
                .when()
                .post("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(403);
        assertEquals(0, headRepository.count("{'app': ?1, 'policyId': ?2}", ControlPlaneFixtures.OTHER, "p-body"));

        String withdrawingMine = policyWithBag("p-body", "[]");
        given().contentType(ContentType.JSON)
                .body(withdrawingMine)
                .when()
                .post("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(201);
        assertEquals(1, headRepository.count("{'app': ?1, 'policyId': ?2}", ControlPlaneFixtures.MINE, "p-body"));
    }

    /** The bag of a simulated request is data for the simulation; it is not the caller's. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void subjectAttributesInASimulatedRequestDoNotAuthorizeTheSimulation() {
        String body = "{\"policy\": " + ControlPlaneFixtures.POLICY_TEMPLATE.formatted("p-sim")
                + ", \"request\": {\"action\": \"document:read\", \"resource\": {\"type\": \"document\"},"
                + " \"subjectAttributes\": {\"apps\": [\"app-other\"]}}}";
        RestAssured.urlEncodingEnabled = false;
        try {
            given().contentType(ContentType.JSON)
                    .body(body)
                    .when()
                    .post("/v1/apps/" + ControlPlaneFixtures.OTHER + "/policies:simulate")
                    .then()
                    .statusCode(403);
            given().contentType(ContentType.JSON)
                    .body(body.replace("[\"app-other\"]", "[]"))
                    .when()
                    .post("/v1/apps/" + ControlPlaneFixtures.MINE + "/policies:simulate")
                    .then()
                    .statusCode(200);
        } finally {
            RestAssured.urlEncodingEnabled = true;
        }
    }

    /** A configuration body carries a claim mapping legitimately; it is data to store, not the gate's mapping. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY),
                @Claim(key = "every_app", value = "[\"app-new\",\"app-other\"]", type = ClaimType.JSON_ARRAY)
            })
    void aMappingInAConfigurationBodyIsNotAppliedToTheWriteThatCarriesIt() {
        given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"apps\": \"every_app\"}}")
                .when()
                .post("/v1/apps/{app}/configuration", "app-new")
                .then()
                .statusCode(403);
        assertTrue(configStore.find("app-new").isEmpty());
    }

    // ── Which configuration the mapping comes from (amendment B.1) ───────────────

    /**
     * The target application maps {@code apps} to a claim that does name it; the reserved application maps it
     * to one that does not. The decision about the target follows the reserved mapping, both ways.
     */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY),
                @Claim(key = "other_apps", value = "[\"app-other\"]", type = ClaimType.JSON_ARRAY)
            })
    void theMappingOfTheApplicationDecidedAboutChangesNoDecision() {
        replaceMapping(ControlPlaneFixtures.OTHER, Map.of("apps", "other_apps"));
        replaceMapping(ControlPlaneFixtures.MINE, Map.of("apps", "other_apps"));

        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(403);
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
    }

    /** The positive side of the same property: changing the reserved mapping does change the decision. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY),
                @Claim(key = "other_apps", value = "[\"app-other\"]", type = ClaimType.JSON_ARRAY)
            })
    void theMappingOfTheReservedApplicationIsTheOneApplied() {
        replaceMapping(ControlPlaneTestSupport.RESERVED_APP, Map.of("apps", "other_apps"));

        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(200);
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(403);
    }

    private void replaceMapping(String app, Map<String, String> mapping) {
        long revision = configStore.find(app).orElseThrow().revision();
        configStore.replace(app, new AppConfigDraft(mapping, null), revision, AuditActor.verified("test"));
    }

    private static String policyWithBag(String policyId, String apps) {
        String policy = ControlPlaneFixtures.POLICY_TEMPLATE.formatted(policyId).strip();
        return policy.substring(0, policy.length() - 1) + ", \"subjectAttributes\": {\"apps\": " + apps + "}}";
    }

    private static List<Class<?>> parameters(Class<?> type, String name) {
        List<Method> methods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name) && Modifier.isPublic(method.getModifiers()))
                .toList();
        assertEquals(1, methods.size(), () -> type.getSimpleName() + " declares " + methods.size() + " public " + name);
        assertFalse(methods.get(0).isVarArgs());
        return List.of(methods.get(0).getParameterTypes());
    }
}
