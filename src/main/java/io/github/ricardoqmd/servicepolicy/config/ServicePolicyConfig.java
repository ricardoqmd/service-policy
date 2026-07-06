package io.github.ricardoqmd.servicepolicy.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Typed configuration for service metadata and authorization markers.
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

    /**
     * Authorization markers for the admin (control-plane) and delegation (data-plane) gates (ADR-013).
     */
    Authz authz();

    interface Authz {
        /** Marker required to author policies via the control-plane endpoints. */
        Marker admin();

        /** Marker required to query on behalf of a subject other than the caller (delegated queries). */
        Marker delegation();
    }

    interface Marker {
        /** Whether the marker is expressed as an OIDC role or an OIDC scope. */
        @WithDefault("role")
        Mode mode();

        /** Role name checked via {@code SecurityIdentity.hasRole()}. Used when {@code mode=role}. */
        Optional<String> role();

        /** Scope value checked in the {@code scope} claim (whitespace-split). Used when {@code mode=scope}. */
        Optional<String> scope();
    }

    /** Determines how an authorization marker is expressed in the OIDC token. */
    enum Mode {
        ROLE,
        SCOPE
    }

    interface Info {
        /** Human-readable name of the service. */
        String name();

        /** Short description of what this service does. */
        String description();

        /** URL of the source code repository. */
        String repository();
    }
}
