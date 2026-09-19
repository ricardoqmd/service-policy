package io.github.ricardoqmd.servicepolicy.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Typed configuration for service metadata, the delegation marker and the control plane.
 *
 * <p>Maps the {@code service-policy.*} properties from {@code application.yml}
 * into a strongly-typed interface. Quarkus generates the implementation at build time.
 *
 * <p>Following 12-factor principles, all values default to environment-friendly
 * placeholders and can be overridden by env vars (e.g. {@code SERVICE_POLICY_INFO_NAME}).
 *
 * <p>There is deliberately no administrative marker here (ADR-033 §5). The control plane is
 * authorized by policy, and no property may restore a global grant.
 */
@ConfigMapping(prefix = "service-policy")
public interface ServicePolicyConfig {

    /**
     * Information about this Service Policy instance, exposed via the /info endpoint.
     */
    Info info();

    /**
     * The delegation marker of the data plane (ADR-013 §5, ADR-032).
     */
    Authz authz();

    /**
     * Bounds the engine places on the work a single evaluation request may ask for (ADR-031).
     */
    Evaluation evaluation();

    /**
     * Per-application authorization of the control plane (ADR-033).
     */
    ControlPlane controlPlane();

    interface Evaluation {
        /**
         * Maximum number of items a single batch may carry.
         *
         * <p>Defaults to 100 — the same maximum the API already enforces for collection page size,
         * so the surface gives one consistent answer to "how much may a single request ask for"
         * (ADR-031 §1). Deployments with different traffic shapes may tune it; a value below 1 fails
         * startup validation ({@link EvaluationConfigValidator}).
         */
        @WithDefault("100")
        int batchMaxSize();
    }

    interface Authz {
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

    interface ControlPlane {
        /**
         * The reserved application that holds the control-plane policy set and the control-plane claim
         * mapping (ADR-033 §6). It is where the rules are kept, never the application they decide about.
         */
        @WithDefault("service-policy-control-plane")
        String reservedApp();

        /** The claim mapping installation seeds into the reserved application's configuration. */
        SubjectAttributes subjectAttributes();

        /** The one caller accepted while the service is not installed (ADR-033 §6). */
        Bootstrap bootstrap();
    }

    interface SubjectAttributes {
        /**
         * Claim path that carries the caller's applications. Required while no installation marker
         * exists — the service does not start without it — and ignored once the marker exists, when the
         * mapping stored in the reserved application's configuration is the only source (ADR-033 §6).
         */
        Optional<String> apps();
    }

    interface Bootstrap {
        /** Claim path that identifies the bootstrap subject in the validated token. */
        @WithDefault("sub")
        String claim();

        /**
         * Value that claim must carry. Absent means installation mode accepts no caller. It grants
         * nothing once the installation marker exists, whether or not it is still configured.
         */
        Optional<String> value();
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
