package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Incoming authorization evaluation request from a Policy Enforcement Point (PEP).
 *
 * @param action   Action to authorize in {@code resource:verb} format (e.g. {@code empleado:read}).
 * @param resource Target resource being accessed.
 * @param context  Optional runtime context attributes (e.g. {@code {"emergency": true}}).
 */
@Schema(description = "Authorization evaluation request from a PEP.")
public record EvaluationRequest(
        @Schema(required = true, description = "Action in 'resource:verb' format, e.g. 'empleado:read'.")
        String action,

        @Schema(required = true, description = "Target resource being accessed.")
        ResourceRef resource,

        @Schema(description = "Optional runtime context attributes, e.g. {\"emergency\": true}.")
        Map<String, Object> context) {}
