package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Incoming authorization evaluation request from a Policy Enforcement Point (PEP).
 *
 * <p>Subject identity is resolved from the validated Bearer JWT by default (ADR-010).
 * An explicit {@code subject} may be provided for delegated queries (ADR-013 §5):
 * absent or equal to the caller's own {@code sub} → self; different from the caller →
 * the delegation marker is required or the request is rejected with 403.
 *
 * <p>Non-identity subject attributes may be asserted via {@code subjectAttributes}, a flat
 * bag sibling to {@code context}. An absent bag means the subject has no asserted attributes
 * (less privilege), not an error.
 *
 * @param app               Application scope for this request (ADR-024); required. Selects which policies are candidates.
 * @param action            Action to authorize in {@code resource:verb} format (e.g. {@code document:read}).
 * @param resource          Target resource being accessed.
 * @param context           Optional runtime context attributes (e.g. {@code {"emergency": true}}).
 * @param subjectAttributes Optional non-identity subject attributes asserted by the caller (e.g. {@code {"area": "A"}}).
 * @param subject           Optional explicit subject; absent or equal to the caller's own {@code sub} → self.
 *                          Different from the caller requires the delegation marker (ADR-013).
 */
@Schema(description = "Authorization evaluation request from a PEP.")
public record EvaluationRequest(
        @Schema(required = true, description = "Application scope for this request (ADR-024), e.g. 'my-app'.")
        String app,

        @Schema(required = true, description = "Action in 'resource:verb' format, e.g. 'document:read'.")
        String action,

        @Schema(required = true, description = "Target resource being accessed.")
        ResourceRef resource,

        @Schema(description = "Optional runtime context attributes, e.g. {\"emergency\": true}.")
        Map<String, Object> context,

        @Schema(
                description =
                        "Optional non-identity subject attributes asserted by the caller, e.g. {\"area\": \"A\"}.")
        Map<String, Object> subjectAttributes,

        @Schema(
                description =
                        "Optional explicit subject; absent or equal to the caller's own sub → self (backwards compatible). "
                                + "Different from the caller requires the delegation marker (ADR-013).")
        String subject) {}
