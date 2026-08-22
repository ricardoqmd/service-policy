package io.github.ricardoqmd.servicepolicy.config;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import io.quarkus.runtime.StartupEvent;

/**
 * Fail-fast startup validator for evaluation bounds configuration (ADR-031 §2).
 *
 * <p>A batch cap below 1 would reject every batch at runtime. The engine refuses to boot instead,
 * the same way a missing authorization marker does (ADR-013 §3) — a misconfiguration is surfaced
 * once at startup rather than as a wall of 400s to callers who did nothing wrong.
 *
 * <p>Kept apart from {@link AuthzConfigValidator}, whose javadoc'd scope is the authorization
 * markers: one validator per concern.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed.
@Singleton
public class EvaluationConfigValidator {

    private final ServicePolicyConfig cfg;

    EvaluationConfigValidator(ServicePolicyConfig cfg) {
        this.cfg = cfg;
    }

    void onStart(@Observes StartupEvent event) {
        int batchMaxSize = cfg.evaluation().batchMaxSize();
        if (batchMaxSize < 1) {
            throw new IllegalStateException(
                    "service-policy.evaluation.batch-max-size must be 1 or greater, but was " + batchMaxSize);
        }
    }
}
