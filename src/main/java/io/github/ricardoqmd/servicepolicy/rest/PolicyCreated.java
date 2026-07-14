package io.github.ricardoqmd.servicepolicy.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response body for a successful policy creation.
 *
 * <p>The application is not echoed here: it is the path coordinate the client just supplied
 * ({@code POST /v1/apps/&#123;app&#125;/policies}, ADR-026). The read views return it.
 *
 * @param policyId the created policy's id, unique within its application.
 * @param version  the created policy's version.
 * @param active   whether the policy is active — always {@code false} on create, which leaves the
 *                 policy inactive until it is explicitly activated (ADR-014, ADR-020).
 */
@Schema(description = "Result of creating a policy.")
public record PolicyCreated(
        @Schema(required = true, description = "The created policy id.")
        String policyId,

        @Schema(required = true, description = "The created policy version.")
        int version,

        @Schema(required = true, description = "Whether the policy is active.")
        boolean active) {}
