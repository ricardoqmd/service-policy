package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneFixtures.Endpoint;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;

/**
 * Installation mode, and the one-way marker that closes it (ADR-033 §6).
 *
 * <p>Each test starts from a store seeded exactly as startup seeds one that has never been installed — the
 * reserved application's configuration, catalogue and baseline policy — and with no installation marker.
 * The test profile's bootstrap claim value is {@value ControlPlaneTestSupport#BOOTSTRAP_SUBJECT}, carried in
 * {@code sub}.
 */
@QuarkusTest
class InstallationModeTest {

    private static final String BOOTSTRAP = ControlPlaneTestSupport.BOOTSTRAP_SUBJECT;

    @Inject
    ControlPlaneFixtures fixtures;

    @Inject
    InstallationStore installationStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @BeforeEach
    void arrange() {
        fixtures.wipe();
        fixtures.seed(ControlPlaneFixtures.MINE);
        fixtures.seed(ControlPlaneFixtures.OTHER);
        fixtures.controlPlane.notInstalled();
    }

    @AfterEach
    void cleanUp() {
        fixtures.wipe();
        fixtures.controlPlane.installed();
    }

    static List<Endpoint> endpoints() {
        return ControlPlaneFixtures.endpoints();
    }

    static List<Endpoint> writeEndpoints() {
        return ControlPlaneFixtures.writeEndpoints();
    }

    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(claims = @Claim(key = "sub", value = BOOTSTRAP))
    void aFreshStoreAcceptsTheBootstrapSubjectAndAReadDoesNotCloseIt() {
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(200);
        given().when()
                .get("/v1/policies")
                .then()
                .statusCode(200)
                .body("data.app", hasItems("app-mine", "app-other", ControlPlaneTestSupport.RESERVED_APP));

        assertFalse(installationStore.marker().installed(), "a read closed installation mode");
    }

    /** A seeded store that nobody has written to yet still refuses everyone but the bootstrap subject. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "console-a"),
                @Claim(
                        key = "apps",
                        value = "[\"app-mine\",\"app-mine-2\",\"service-policy-control-plane\"]",
                        type = ClaimType.JSON_ARRAY)
            })
    void aFreshStoreRefusesEveryoneElseEvenWithTheBaselineSeeded(Endpoint endpoint) {
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(endpoint.send(ControlPlaneFixtures.MINE)));
        assertFalse(installationStore.marker().installed());
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "console-a"),
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY)
            })
    void aFreshStoreShowsEveryoneElseAnEmptyMergedCatalogue() {
        assertEquals(
                MergedCatalogueScopeTest.EMPTY_PAGE_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().when().get("/v1/policies")));
    }

    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(claims = @Claim(key = "sub", value = BOOTSTRAP))
    void aFailedWriteByTheBootstrapSubjectDoesNotCloseInstallationMode() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/apps/{app}/configuration", "app-new")
                .then()
                .statusCode(400);
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"7\"")
                .body("{\"version\": 1}")
                .when()
                .post("/v1/apps/{app}/policies/p-1/activate", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(412);

        assertFalse(installationStore.marker().installed());
    }

    /**
     * The first successful write closes installation mode, and from then on the bootstrap claim value grants
     * nothing — though it is still configured in this profile.
     */
    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(claims = @Claim(key = "sub", value = BOOTSTRAP))
    void theFirstSuccessfulWriteClosesInstallationModeForGood() {
        given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"unit\": \"unit\"}}")
                .when()
                .post("/v1/apps/{app}/configuration", "app-new")
                .then()
                .statusCode(201);
        assertTrue(installationStore.marker().installed());

        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().when().get("/v1/apps/{app}/configuration", "app-new")));
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().contentType(ContentType.JSON)
                        .body("{\"subjectAttributes\": {\"unit\": \"unit\"}}")
                        .when()
                        .post("/v1/apps/{app}/configuration", "app-newer")));
        assertEquals(
                MergedCatalogueScopeTest.EMPTY_PAGE_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().when().get("/v1/policies")));
    }

    /**
     * The set the case below runs over is the complete set of control-plane writes. Named, not counted: a
     * write added to the production code and forgotten here would otherwise leave the case silently smaller.
     */
    @Test
    void theWritesAreTheseTen() {
        assertEquals(
                List.of(
                        "policy.create",
                        "policy.append",
                        "policy.activate",
                        "policy.deactivate",
                        "catalogue.create",
                        "catalogue.replace",
                        "catalogue.delete",
                        "configuration.create",
                        "configuration.replace",
                        "configuration.delete"),
                writeEndpoints().stream().map(Endpoint::name).toList());
    }

    /**
     * Each write, on its own, closes installation mode when it is the bootstrap subject's first successful
     * one (ADR-033 §6). One case per endpoint: dropping the call from any single endpoint turns exactly that
     * case red, which a suite that exercised one write could not do.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("writeEndpoints")
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(claims = @Claim(key = "sub", value = BOOTSTRAP))
    void everyWriteClosesInstallationModeAsTheFirstSuccessfulOne(Endpoint endpoint) {
        assertFalse(installationStore.marker().installed(), "the store was installed before the write");

        String app = endpoint.needsFreshApp() ? ControlPlaneFixtures.MINE_2 : ControlPlaneFixtures.MINE;
        assertEquals(endpoint.success(), endpoint.send(app).statusCode(), endpoint.name());

        assertTrue(installationStore.marker().installed(), endpoint.name() + " did not close installation mode");
    }

    /**
     * What the migration note of the README says, measured. The write that closes installation is an
     * ordinary control-plane write, and not every attempt closes: a {@code PUT} of the reserved
     * application's configuration by a bootstrap credential that does not itself carry the reserved
     * application is refused by the self-lockout guard of ADR-033 §6 and leaves installation mode open.
     */
    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(claims = @Claim(key = "sub", value = BOOTSTRAP))
    void theReservedConfigurationWriteOfABootstrapWithoutTheReservedApplicationDoesNotClose() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("{\"subjectAttributes\": {\"apps\": \"apps\"}}")
                .when()
                .put("/v1/apps/{app}/configuration", ControlPlaneTestSupport.RESERVED_APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"));

        assertFalse(installationStore.marker().installed(), "a refused write closed installation mode");
    }

    /**
     * The recommendation of that same note: the identical write, by a bootstrap credential that already
     * carries the reserved application, is accepted and closes installation — so the mapping is proven
     * against a real token before the close becomes irreversible.
     */
    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = BOOTSTRAP),
                @Claim(key = "apps", value = "[\"service-policy-control-plane\"]", type = ClaimType.JSON_ARRAY)
            })
    void theReservedConfigurationWriteOfABootstrapThatCarriesItClosesInstallation() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("{\"subjectAttributes\": {\"apps\": \"apps\"}}")
                .when()
                .put("/v1/apps/{app}/configuration", ControlPlaneTestSupport.RESERVED_APP)
                .then()
                .statusCode(200);

        assertTrue(installationStore.marker().installed(), "the closing write did not close installation mode");
    }

    /** Re-running installation against a store that already carries the marker changes nothing. */
    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(claims = @Claim(key = "sub", value = BOOTSTRAP))
    void reRunningInstallationOnAnInstalledStoreDoesNotReopenIt() {
        installationStore.recordInstalled("someone-else", ControlPlaneTestSupport.RESERVED_APP);
        fixtures.controlPlane.installation.prepare();

        assertTrue(installationStore.marker().installed());
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(403);
    }

    /** Deleting every policy — the baseline included — does not reopen installation mode. */
    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = BOOTSTRAP),
                @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY)
            })
    void installationModeIsNeverInferredFromTheAbsenceOfPolicies() {
        installationStore.recordInstalled("installer", ControlPlaneTestSupport.RESERVED_APP);
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);

        headRepository.deleteAll();
        versionRepository.deleteAll();

        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().when().get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)));
        assertTrue(installationStore.marker().installed());
    }

    /** The positive control of the refusals above: once installed, the bootstrap subject is an ordinary caller. */
    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = BOOTSTRAP),
                @Claim(key = "apps", value = "[\"app-mine\"]", type = ClaimType.JSON_ARRAY)
            })
    void onceInstalledTheBootstrapSubjectIsAuthorizedByPolicyLikeAnyoneElse() {
        installationStore.recordInstalled("installer", ControlPlaneTestSupport.RESERVED_APP);
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }
}
