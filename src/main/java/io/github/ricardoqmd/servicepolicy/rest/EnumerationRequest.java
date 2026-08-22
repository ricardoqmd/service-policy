package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Body of {@code POST /v1/apps/&#123;app&#125;/permissions:enumerate} (ADR-032).
 *
 * <p>Subject provenance is the hybrid rule of ADR-013 §5, unchanged: absent or equal to the caller's
 * own {@code sub} → self; different from the caller → the delegation marker is required or the
 * request is rejected with 403. A backend enumerating on behalf of a user needs the same marker it
 * already needs to evaluate on behalf of that user.
 *
 * <p>Subject attributes are caller-asserted (ADR-010) and are the <em>only</em> source on this
 * transport: claim derivation does not run here. On this path the validated token belongs to the
 * calling backend's service account, not to the subject being enumerated, so deriving attributes
 * from it and attributing them to a different subject would be the forgery ADR-010 exists to
 * prevent. There is consequently no merge and no precedence rule between pushed and derived
 * attributes — one transport reads claims, the other reads this body.
 *
 * <p>An absent or empty bag is not an error: it means the subject has no asserted attributes, which
 * resolves fewer conditions and yields <em>more</em> {@code conditional} pairs, never fewer.
 *
 * <p>The application scope is NOT part of this body (ADR-026): it is the path coordinate. A body
 * carrying an {@code app} field is rejected with 400 — the field is simply absent from this record,
 * and strict deserialization turns the stray property into the problem response, the same mechanism
 * that guards {@code EvaluationRequest}.
 *
 * @param subject           Optional explicit subject; absent or equal to the caller's own {@code sub} → self.
 *                          Different from the caller requires the delegation marker (ADR-013 §5).
 * @param subjectAttributes Optional non-identity subject attributes asserted by the caller
 *                          (e.g. {@code {"area": "A"}}). Absent or empty is not an error.
 */
@Schema(description = "Permission enumeration request with caller-asserted subject attributes.")
public record EnumerationRequest(
        @Schema(
                description = "Optional explicit subject; absent or equal to the caller's own sub → self. "
                        + "Different from the caller requires the delegation marker (ADR-013).")
        String subject,

        @Schema(
                description =
                        "Optional non-identity subject attributes asserted by the caller, e.g. {\"area\": \"A\"}. "
                                + "Absent or empty is not an error: it yields more conditional pairs, never fewer.")
        Map<String, Object> subjectAttributes) {}
