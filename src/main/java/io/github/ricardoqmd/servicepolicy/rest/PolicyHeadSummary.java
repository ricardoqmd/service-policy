package io.github.ricardoqmd.servicepolicy.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Lean list item for {@code GET /v1/policies} (ADR-017): head metadata without the active content
 * (the rule AST). Use {@code ?view=full} to obtain {@link PolicyHeadView} items instead.
 */
@Schema(description = "Policy head summary (no content).")
public record PolicyHeadSummary(
        String policyId, String app, String resourceType, Integer activeVersion, long revision, AuditView audit) {}
