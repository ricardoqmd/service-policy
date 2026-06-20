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
        // TODO Phase 2: check MongoDB connection.
        // TODO Phase 3: check Keycloak JWKS endpoint reachability.
        return HealthCheckResponse.up("service-policy-ready");
    }
}
