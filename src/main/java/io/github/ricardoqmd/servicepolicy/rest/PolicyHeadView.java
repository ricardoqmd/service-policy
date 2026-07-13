package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Full view of a policy head for {@code GET /v1/policies/{id}} (and {@code GET /v1/policies?view=full}):
 * head metadata plus {@code activeContent}, the active version's content in the document shape.
 * {@code activeContent} is {@code null} when the policy has no active version.
 */
@Schema(description = "Full policy head (with active content).")
public record PolicyHeadView(
        String policyId,
        String app,
        String resourceType,
        Integer activeVersion,
        long revision,
        AuditView audit,
        Map<String, Object> activeContent) {}
