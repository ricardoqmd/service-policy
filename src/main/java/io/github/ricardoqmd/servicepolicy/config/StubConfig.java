package io.github.ricardoqmd.servicepolicy.config;

import java.util.List;
import java.util.Map;

import io.smallrye.config.ConfigMapping;

/**
 * Typed configuration for the deterministic stub evaluator.
 *
 * <p>Maps the {@code service-policy.stub.*} properties from {@code application.yml}.
 * Quarkus generates the implementation at build time.
 *
 * <p>Replace with persistence-backed config in Phase 2.
 */
@ConfigMapping(prefix = "service-policy.stub")
public interface StubConfig {

    /** Version tag returned in every decision. Update whenever stub rules change. */
    String policyVersion();

    /**
     * HTTP verb segments (the part after {@code :} in an action string) that the stub denies by
     * default.
     */
    List<String> deniedVerbs();

    /** Per-application permitted action lists. Key = app name, value = list of allowed actions. */
    Map<String, List<String>> permissions();
}
