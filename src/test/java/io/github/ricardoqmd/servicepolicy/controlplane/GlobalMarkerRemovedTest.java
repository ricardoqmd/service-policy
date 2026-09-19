package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneFixtures.Endpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;

/**
 * The global administrative marker is gone, with no switch that brings it back (ADR-033 §5).
 *
 * <p>Two halves. A caller carrying what the marker used to be — the {@code authz-admin} role and scope — is
 * refused everywhere, with the literal denial. And the configuration surface is enumerated completely and
 * pinned: a property added to it, of whatever name, fails {@link #theConfigurationSurfaceIsExactlyThis} and
 * has to be justified against this ADR before the pin is moved. None of the properties below grants
 * control-plane access by itself: the bootstrap value grants nothing once installed (see
 * {@code InstallationModeTest}), and the reserved application and claim path only feed the policy decision.
 */
@QuarkusTest
class GlobalMarkerRemovedTest {

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
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    @OidcSecurity(claims = @Claim(key = "scope", value = "authz-admin openid"))
    void theFormerAdministrativeMarkerGrantsNothing(Endpoint endpoint) {
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(endpoint.send(ControlPlaneFixtures.MINE)));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    @OidcSecurity(claims = @Claim(key = "scope", value = "authz-admin openid"))
    void theFormerAdministrativeMarkerSeesNothingInTheMergedCatalogue() {
        assertEquals(
                MergedCatalogueScopeTest.EMPTY_PAGE_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().when().get("/v1/policies")));
    }

    /** Every property the service reads under its prefix. A new one fails this until it is justified. */
    @Test
    void theConfigurationSurfaceIsExactlyThis() {
        Set<String> expected = new TreeSet<>(Set.of(
                "service-policy.info.name",
                "service-policy.info.description",
                "service-policy.info.repository",
                "service-policy.authz.delegation.mode",
                "service-policy.authz.delegation.role",
                "service-policy.authz.delegation.scope",
                "service-policy.evaluation.batch-max-size",
                "service-policy.control-plane.reserved-app",
                "service-policy.control-plane.subject-attributes.apps",
                "service-policy.control-plane.bootstrap.claim",
                "service-policy.control-plane.bootstrap.value"));

        Set<String> mapped = new TreeSet<>();
        collect("service-policy", ServicePolicyConfig.class, mapped);
        assertEquals(expected, mapped);

        Set<String> configured = new TreeSet<>();
        StreamSupport.stream(ConfigProvider.getConfig().getPropertyNames().spliterator(), false)
                .filter(name -> name.startsWith("service-policy."))
                .forEach(configured::add);
        assertTrue(expected.containsAll(configured), () -> "configured beyond the surface: " + configured);
    }

    /** Walks a config mapping the way SmallRye names it: kebab-case, one segment per nested group. */
    private static void collect(String prefix, Class<?> group, Set<String> into) {
        for (Method method : group.getDeclaredMethods()) {
            if (method.isSynthetic()
                    || method.isDefault()
                    || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String name = prefix + "." + kebab(method.getName());
            Class<?> type = method.getReturnType();
            if (type.isInterface() && type.getEnclosingClass() == ServicePolicyConfig.class) {
                collect(name, type, into);
            } else if (type == java.util.Optional.class
                    && method.getGenericReturnType() instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> inner
                    && inner.isInterface()
                    && inner.getEnclosingClass() == ServicePolicyConfig.class) {
                collect(name, inner, into);
            } else {
                into.add(name);
            }
        }
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
