package io.github.ricardoqmd.servicepolicy.rest;

import java.time.Instant;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;

/**
 * Service metadata endpoint.
 *
 * <p>Exposes basic information about this Service Policy instance:
 * what it is, what version, when it started. Useful for verifying which
 * version is deployed and for smoke-testing reachability.
 *
 * <p>It also carries the bounds a client has to respect, which is why they belong
 * here rather than only in documentation: a number a caller must obey and cannot
 * read is a number it will get wrong.
 *
 * <p>This endpoint is intentionally public (no auth) so it can be used
 * by monitoring systems and during initial setup. Nothing here may become
 * anything but public: it answers before anyone has a token.
 */
@Path("/info")
@Tag(name = "Metadata", description = "Service metadata and information")
public class InfoResource {

    private static final Instant STARTED_AT = Instant.now();

    private final ServicePolicyConfig config;

    InfoResource(ServicePolicyConfig config) {
        this.config = config;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Get service information",
            description = "Returns metadata about this Service Policy instance, "
                    + "including name, version, repository URL, and uptime.")
    public Map<String, Object> info() {
        return Map.of(
                "name", config.info().name(),
                "description", config.info().description(),
                "repository", config.info().repository(),
                "startedAt", STARTED_AT.toString(),
                "uptime", java.time.Duration.between(STARTED_AT, Instant.now()).toString(),
                // The one bound a caller must know BEFORE it sends anything (ADR-031). A client that
                // cannot read it has to discover the cap by having a batch refused, and a deployment
                // that lowers it turns every listing of every consumer into an error until each one
                // is reconfigured by hand.
                "evaluation", Map.of("batchMaxSize", config.evaluation().batchMaxSize()));
    }
}
