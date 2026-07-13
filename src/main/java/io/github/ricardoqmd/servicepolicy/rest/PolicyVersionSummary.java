package io.github.ricardoqmd.servicepolicy.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Lean list item for {@code GET /v1/policies/{id}/versions} (ADR-017): version metadata without the
 * rule content. The full content of a single version is available at
 * {@code GET /v1/policies/{id}/versions/{version}}.
 */
@Schema(description = "Policy version summary (no content).")
public record PolicyVersionSummary(String policyId, String app, int version, String resourceType, AuditView audit) {}
