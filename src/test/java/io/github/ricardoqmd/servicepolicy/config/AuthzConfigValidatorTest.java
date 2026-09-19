package io.github.ricardoqmd.servicepolicy.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the fail-fast startup validation in {@link AuthzConfigValidator} (ADR-013 §3).
 * Exercises the validator directly with stub configs — no Quarkus container needed.
 *
 * <p>Only the delegation marker is validated: the administrative marker no longer exists (ADR-033 §5).
 */
class AuthzConfigValidatorTest {

    @Test
    void validatorPassesWithDefaultRoleConfig() {
        AuthzConfigValidator v = new AuthzConfigValidator(
                config(marker(ServicePolicyConfig.Mode.ROLE, Optional.of("pdp-client"), Optional.empty())));
        assertDoesNotThrow(() -> v.onStart(null));
    }

    @Test
    void validatorPassesWithScopeModeWhenScopeIsSet() {
        AuthzConfigValidator v = new AuthzConfigValidator(
                config(marker(ServicePolicyConfig.Mode.SCOPE, Optional.empty(), Optional.of("pdp-scope"))));
        assertDoesNotThrow(() -> v.onStart(null));
    }

    @Test
    void validatorThrowsWhenDelegationModeIsRoleButRoleIsAbsent() {
        AuthzConfigValidator v = new AuthzConfigValidator(
                config(marker(ServicePolicyConfig.Mode.ROLE, Optional.empty(), Optional.empty())));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> v.onStart(null));
        assertTrue(ex.getMessage().contains("service-policy.authz.delegation.role"));
    }

    @Test
    void validatorThrowsWhenDelegationModeIsScopeButScopeIsAbsent() {
        AuthzConfigValidator v = new AuthzConfigValidator(
                config(marker(ServicePolicyConfig.Mode.SCOPE, Optional.empty(), Optional.empty())));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> v.onStart(null));
        assertTrue(ex.getMessage().contains("service-policy.authz.delegation.scope"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ServicePolicyConfig.Marker marker(
            ServicePolicyConfig.Mode mode, Optional<String> role, Optional<String> scope) {
        return new ServicePolicyConfig.Marker() {
            @Override
            public ServicePolicyConfig.Mode mode() {
                return mode;
            }

            @Override
            public Optional<String> role() {
                return role;
            }

            @Override
            public Optional<String> scope() {
                return scope;
            }
        };
    }

    private static ServicePolicyConfig config(ServicePolicyConfig.Marker delegation) {
        return new ServicePolicyConfig() {
            @Override
            public Info info() {
                return null;
            }

            @Override
            public Evaluation evaluation() {
                return null;
            }

            @Override
            public ControlPlane controlPlane() {
                return null;
            }

            @Override
            public Authz authz() {
                return () -> delegation;
            }
        };
    }
}
