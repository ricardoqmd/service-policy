package io.github.ricardoqmd.servicepolicy.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneFixtures.Endpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;

/**
 * A control-plane refusal does not depend on whether the application exists (ADR-033 §2).
 *
 * <p>How this is asserted matters, because it is easy to assert wrongly:
 *
 * <ul>
 *   <li>the <em>complete</em> response — status line, every header, body — is compared with a literal
 *       written out below, never with another response: two responses built by the same code would change
 *       together and keep a comparison green while the property broke;
 *   <li>it is exercised with an application that does not exist ({@value ControlPlaneFixtures#NONCE}). The
 *       property is not that the response avoids echoing a name the caller sent — echoing what the caller
 *       knows discloses nothing — but that it does not change with the application's existence, and only an
 *       identifier that exists nowhere exercises that;
 *   <li>and an application that does exist and is not the caller's ({@value ControlPlaneFixtures#OTHER}) is
 *       held to the same literal.
 * </ul>
 *
 * <p>Every refusal has a positive control: the same request to an application the caller holds succeeds, so
 * a suite of refusals cannot pass merely because everything is refused.
 */
@QuarkusTest
class ControlPlaneRefusalOnTheWireTest {

    /** The one control-plane denial, byte for byte, as a client receives it. */
    static final String DENIED_ON_THE_WIRE = """
            HTTP/1.1 403 Forbidden
            content-length: 202
            content-type: application/problem+json

            {"type":"https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#forbidden",\
            "code":"FORBIDDEN","title":"Forbidden","status":403,\
            "detail":"not authorized for this control-plane operation."}""";

    @Inject
    ControlPlaneFixtures fixtures;

    @BeforeEach
    void arrange() {
        fixtures.arrange();
    }

    @AfterEach
    void cleanUp() {
        fixtures.wipe();
    }

    static List<Endpoint> endpoints() {
        return ControlPlaneFixtures.endpoints();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void aRefusalForAnApplicationThatDoesNotExistIsTheLiteralDenial(Endpoint endpoint) {
        assertEquals(DENIED_ON_THE_WIRE, ControlPlaneFixtures.wire(endpoint.send(ControlPlaneFixtures.NONCE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void aRefusalForAnApplicationThatExistsAndIsNotTheCallersIsTheLiteralDenial(Endpoint endpoint) {
        assertEquals(DENIED_ON_THE_WIRE, ControlPlaneFixtures.wire(endpoint.send(ControlPlaneFixtures.OTHER)));
    }

    /** The positive control of the two tests above: the gate answers the question, it does not refuse all. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void theSameRequestForAnApplicationTheCallerHoldsSucceeds(Endpoint endpoint) {
        String app = endpoint.needsFreshApp() ? ControlPlaneFixtures.MINE_2 : ControlPlaneFixtures.MINE;
        assertEquals(endpoint.success(), endpoint.send(app).statusCode(), endpoint.name());
    }

    /** A caller holding no application at all is refused identically. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "no-apps")
    @OidcSecurity(claims = @Claim(key = "sub", value = "no-apps"))
    void aCallerWithoutTheClaimIsRefusedWithTheLiteralDenial(Endpoint endpoint) {
        assertEquals(DENIED_ON_THE_WIRE, ControlPlaneFixtures.wire(endpoint.send(ControlPlaneFixtures.NONCE)));
        assertEquals(DENIED_ON_THE_WIRE, ControlPlaneFixtures.wire(endpoint.send(ControlPlaneFixtures.MINE)));
    }
}
