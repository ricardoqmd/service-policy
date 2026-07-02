package io.github.ricardoqmd.servicepolicy.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response body for a successful policy creation.
 *
 * @param policyId the created policy's id.
 * @param version  the created policy's version.
 * @param active   whether the policy is active (always true for POST /v1/policies; see ADR-012).
 */
@Schema(description = "Result of creating a policy.")
public record PolicyCreated(
        @Schema(required = true, description = "The created policy id.")
        String policyId,

        @Schema(required = true, description = "The created policy version.")
        int version,

        @Schema(required = true, description = "Whether the policy is active.")
        boolean active) {}
