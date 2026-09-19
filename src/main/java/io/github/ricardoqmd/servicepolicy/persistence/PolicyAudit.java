package io.github.ricardoqmd.servicepolicy.persistence;

/**
 * Read model for the audit metadata carried by a policy head or version (ADR-014). Timestamps are
 * ISO-8601 strings to stay free of date-codec concerns.
 *
 * <p>{@code createdBy} is the calling credential, as it has always been. {@code subject} and
 * {@code subjectProvenance} record who the write was made on behalf of and whether that identity was
 * verified or declared (ADR-033 §4); both are {@code null} only on entries written before they existed.
 * Documents seeded by installation carry all three, with the installation identity.
 */
public record PolicyAudit(
        String createdBy, String createdAt, String changeReason, String subject, SubjectProvenance subjectProvenance) {}
