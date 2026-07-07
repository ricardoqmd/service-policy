package io.github.ricardoqmd.servicepolicy.persistence;

/**
 * Read model for the audit metadata carried by a policy head or version (ADR-014). Values are
 * populated by the write operations (create/activate) landing in later slices; this slice only
 * projects whatever is stored. Timestamps are ISO-8601 strings to stay free of date-codec concerns.
 */
public record PolicyAudit(String createdBy, String createdAt, String changeReason) {}
