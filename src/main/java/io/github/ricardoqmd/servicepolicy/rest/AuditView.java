package io.github.ricardoqmd.servicepolicy.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Wire shape for audit metadata on a policy head or version (ADR-014). */
@Schema(description = "Audit metadata (who/when/why).")
public record AuditView(String createdBy, String createdAt, String changeReason) {}
