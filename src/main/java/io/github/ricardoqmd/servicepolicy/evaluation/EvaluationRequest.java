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
 * <p>The application scope is NOT part of this body (ADR-026): it is the path coordinate of
 * {@code POST /v1/apps/&#123;app&#125;/evaluate}. A body carrying an {@code app} field is rejected
 * with 400, so route and payload can never disagree.
 *
 * @param action            Action to authorize, as {@code verb} or {@code type:verb} (e.g. {@code document:read}),
 *                          split at the first colon. A prefix, when present, must be {@code resource.type}
 *                          exactly, or the request is refused with 400 (ADR-036) —
 *                          unless it is a batch item whose {@code resource.type} is absent or blank.
 * @param resource          Target resource being accessed.
 * @param context           Optional runtime context attributes (e.g. {@code {"emergency": true}}).
 * @param subjectAttributes Optional non-identity subject attributes asserted by the caller (e.g. {@code {"area": "A"}}).
 * @param subject           Optional explicit subject; absent or equal to the caller's own {@code sub} → self.
 *                          Different from the caller requires the delegation marker (ADR-013).
 */
@Schema(description = "Authorization evaluation request from a PEP.")
public record EvaluationRequest(
        @Schema(
                required = true,
                description = "Action as 'verb' or 'type:verb', split at the first colon, e.g. 'document:read'."
                        + " A prefix, when present, must be resource.type exactly; otherwise 400"
                        + " ACTION_RESOURCE_TYPE_MISMATCH (ADR-036). A blank verb after the colon is 400"
                        + " BAD_REQUEST instead, and a batch item whose resource.type is absent or blank is"
                        + " not checked.")
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
