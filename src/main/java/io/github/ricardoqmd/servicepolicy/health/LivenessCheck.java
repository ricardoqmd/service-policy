package io.github.ricardoqmd.servicepolicy.health;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe: indicates whether the service is alive.
 *
 * <p>Kubernetes (or any orchestrator) uses this to decide if it should restart
 * the container. A failing liveness check means the JVM is in an unrecoverable state
 * (deadlock, OOM imminent) and the only fix is restart.
 *
 * <p>This check should NEVER depend on external systems (database, network) because
 * those failures should not trigger restarts — that's what readiness is for.
 *
 * <p>Exposed at {@code GET /q/health/live}.
 */
@Liveness
@ApplicationScoped
public class LivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        // Si llegamos aquí, el JVM está respondiendo y la app está cargada.
        // Eso es todo lo que liveness necesita confirmar.
        return HealthCheckResponse.up("service-policy-alive");
    }
}
