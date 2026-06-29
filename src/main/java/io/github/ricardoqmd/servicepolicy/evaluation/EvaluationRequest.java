package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Incoming authorization evaluation request from a Policy Enforcement Point (PEP).
 *
 * <p>The subject identity is never carried here — it is resolved from the Bearer JWT. The caller
 * may, however, assert non-identity subject attributes via {@code subjectAttributes}, a flat bag
 * sibling to {@code context} (ADR-010). An absent bag means the subject has no asserted
 * attributes (less privilege), not an error.
 *
 * @param action            Action to authorize in {@code resource:verb} format (e.g. {@code document:read}).
 * @param resource          Target resource being accessed.
 * @param context           Optional runtime context attributes (e.g. {@code {"emergency": true}}).
 * @param subjectAttributes Optional non-identity subject attributes asserted by the caller (e.g. {@code {"area": "A"}}).
 */
@Schema(description = "Authorization evaluation request from a PEP.")
public record EvaluationRequest(
        @Schema(required = true, description = "Action in 'resource:verb' format, e.g. 'document:read'.")
        String action,

        @Schema(required = true, description = "Target resource being accessed.")
        ResourceRef resource,

        @Schema(description = "Optional runtime context attributes, e.g. {\"emergency\": true}.")
        Map<String, Object> context,

        @Schema(
                description =
                        "Optional non-identity subject attributes asserted by the caller, e.g. {\"area\": \"A\"}.")
        Map<String, Object> subjectAttributes) {}
