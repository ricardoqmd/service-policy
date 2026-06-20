package io.github.ricardoqmd.servicepolicy.config;

import io.smallrye.config.ConfigMapping;

/**
 * Typed configuration for service metadata.
 *
 * <p>Maps the {@code service-policy.*} properties from {@code application.yml}
 * into a strongly-typed interface. Quarkus generates the implementation at build time.
 *
 * <p>Following 12-factor principles, all values default to environment-friendly
 * placeholders and can be overridden by env vars (e.g. {@code SERVICE_POLICY_INFO_NAME}).
 */
@ConfigMapping(prefix = "service-policy")
public interface ServicePolicyConfig {

    /**
     * Information about this Service Policy instance, exposed via the /info endpoint.
     */
    Info info();

    interface Info {
        /** Human-readable name of the service. */
        String name();

        /** Short description of what this service does. */
        String description();

        /** URL of the source code repository. */
        String repository();
    }
}
