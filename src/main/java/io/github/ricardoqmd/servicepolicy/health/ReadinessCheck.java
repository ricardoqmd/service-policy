package io.github.ricardoqmd.servicepolicy.health;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe: indicates whether the service can receive traffic.
 *
 * <p>Kubernetes (or load balancers) uses this to decide if it should route
 * requests to this instance. A failing readiness check means the service is alive
 * but not ready (still warming up, lost DB connection, dependencies down).
 *
 * <p>This is where checks for external dependencies belong. In Phase 1 we only
 * have the JVM itself, so this returns UP. In Phase 2 we'll add checks for
 * MongoDB connectivity. In Phase 3, for Keycloak JWKS reachability.
 *
 * <p>Exposed at {@code GET /q/health/ready}.
 */
@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        // Phase 2 will add a MongoDB connectivity check here.
        // Phase 3 will add a Keycloak JWKS endpoint reachability check here.
        return HealthCheckResponse.up("service-policy-ready");
    }
}
