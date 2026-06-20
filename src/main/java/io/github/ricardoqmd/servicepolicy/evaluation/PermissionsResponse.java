package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response to a permissions listing request.
 *
 * <p>Returns a flat, cacheable list of actions the subject is permitted to perform
 * within a given application context.
 *
 * @param subject       Resolved subject identifier from the JWT.
 * @param app           Application context for which permissions are listed.
 * @param permissions   Permitted action strings in {@code resource:verb} format.
 * @param policyVersion Version of the active policy set.
 * @param evaluatedAt   ISO-8601 instant when this response was generated.
 */
@Schema(description = "Cacheable flat list of permitted actions for the authenticated subject.")
public record PermissionsResponse(
        @Schema(required = true, description = "Resolved subject identifier from the JWT.")
        String subject,

        @Schema(required = true, description = "Application context for which permissions are listed.")
        String app,

        @Schema(required = true, description = "Permitted actions in 'resource:verb' format.")
        List<String> permissions,

        @Schema(required = true, description = "Policy version that produced this response.")
        String policyVersion,

        @Schema(required = true, description = "ISO-8601 instant when this response was generated.")
        String evaluatedAt) {}
