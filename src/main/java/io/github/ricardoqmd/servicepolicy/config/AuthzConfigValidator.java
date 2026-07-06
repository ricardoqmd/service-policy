package io.github.ricardoqmd.servicepolicy.config;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Fail-fast startup validator for authorization marker configuration (ADR-013 §3).
 *
 * <p>Validates that each marker's active mode has a corresponding non-blank value.
 * If the active mode's value is missing, the application fails to start with a clear message.
 * An inactive mode's value triggers a WARN and is otherwise ignored (config smell, not fatal).
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed.
@Singleton
public class AuthzConfigValidator {

    private static final Logger log = Logger.getLogger(AuthzConfigValidator.class);

    private final ServicePolicyConfig cfg;

    AuthzConfigValidator(ServicePolicyConfig cfg) {
        this.cfg = cfg;
    }

    void onStart(@Observes StartupEvent event) {
        validate("admin", cfg.authz().admin());
        validate("delegation", cfg.authz().delegation());
    }

    private void validate(String name, ServicePolicyConfig.Marker marker) {
        switch (marker.mode()) {
            case ROLE -> {
                if (marker.role().filter(r -> !r.isBlank()).isEmpty()) {
                    throw new IllegalStateException(
                            "service-policy.authz." + name + ".role must be set when mode=role");
                }
                marker.scope()
                        .filter(s -> !s.isBlank())
                        .ifPresent(s -> log.warnf(
                                "service-policy.authz.%s.scope is configured but mode=role; the scope value is ignored",
                                name));
            }
            case SCOPE -> {
                if (marker.scope().filter(s -> !s.isBlank()).isEmpty()) {
                    throw new IllegalStateException(
                            "service-policy.authz." + name + ".scope must be set when mode=scope");
                }
                marker.role()
                        .filter(r -> !r.isBlank())
                        .ifPresent(r -> log.warnf(
                                "service-policy.authz.%s.role is configured but mode=scope; the role value is ignored",
                                name));
            }
        }
    }
}
