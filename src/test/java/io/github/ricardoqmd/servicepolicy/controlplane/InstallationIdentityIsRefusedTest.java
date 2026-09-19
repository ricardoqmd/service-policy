package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneFixtures.Endpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;

/**
 * The installation identity is installation's alone (ADR-033 §4): a caller whose subject resolves to it — through
 * {@code sub}, or {@code preferred_username} when the token has no {@code sub} — is denied every control-plane
 * call, however much its token carries, so no caller can write a document carrying installation's audit marks.
 */
@QuarkusTest
class InstallationIdentityIsRefusedTest {

    private static final String EVERYTHING =
            "[\"app-mine\",\"app-mine-2\",\"app-other\",\"service-policy-control-plane\"]";

    @Inject
    ControlPlaneFixtures fixtures;

    @BeforeEach
    void arrange() {
        fixtures.wipe();
        fixtures.seed(ControlPlaneFixtures.MINE);
        fixtures.controlPlane.installed();
    }

    @AfterEach
    void cleanUp() {
        fixtures.wipe();
        fixtures.controlPlane.installed();
    }

    static List<Endpoint> endpoints() {
        return ControlPlaneFixtures.endpoints();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "carrier")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "service-policy:installation"),
                @Claim(key = "apps", value = EVERYTHING, type = ClaimType.JSON_ARRAY)
            })
    void aTokenWhoseSubIsTheInstallationIdentityIsDenied(Endpoint endpoint) {
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(endpoint.send(ControlPlaneFixtures.MINE)));
    }

    /** No {@code sub}: the test identity's name reaches the service as {@code preferred_username}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "service-policy:installation")
    @OidcSecurity(claims = @Claim(key = "apps", value = EVERYTHING, type = ClaimType.JSON_ARRAY))
    void aTokenWhosePreferredUsernameIsTheInstallationIdentityIsDenied(Endpoint endpoint) {
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(endpoint.send(ControlPlaneFixtures.MINE)));
    }

    @Test
    @TestSecurity(user = "carrier")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "service-policy:installation"),
                @Claim(key = "apps", value = EVERYTHING, type = ClaimType.JSON_ARRAY)
            })
    void theInstallationIdentityReadsAnEmptyMergedCatalogue() {
        assertEquals(
                MergedCatalogueScopeTest.EMPTY_PAGE_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().when().get("/v1/policies")));
    }

    /** The positive control: the same token with an ordinary subject administers what it carries. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "carrier")
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = "an-ordinary-subject"),
                @Claim(key = "apps", value = EVERYTHING, type = ClaimType.JSON_ARRAY)
            })
    void anOrdinarySubjectWithTheSameClaimsIsUnaffected(Endpoint endpoint) {
        String app = endpoint.needsFreshApp() ? ControlPlaneFixtures.MINE_2 : ControlPlaneFixtures.MINE;
        assertEquals(endpoint.success(), endpoint.send(app).statusCode(), endpoint.name());
    }
}
