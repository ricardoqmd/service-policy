package io.github.ricardoqmd.servicepolicy.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire shape for audit metadata on a policy head or version (ADR-014, ADR-033 §4).
 *
 * <p>{@code createdBy} is the calling credential — the identity of the validated token — as it always was.
 * {@code subject} is who the caller says acted, and {@code subjectProvenance} says whether this engine
 * verified that identity ({@code VERIFIED}: it is the token's own) or the caller merely declared it
 * ({@code DECLARED}). Those two are omitted on the only entries that do not carry them: those written
 * before the fields existed. Documents seeded by installation carry all three (ADR-033 §4).
 */
@Schema(description = "Audit metadata: who called, on whose behalf, whether that was verified, when and why.")
public record AuditView(
        @Schema(description = "The calling credential: the subject of the validated token.")
        String createdBy,

        String createdAt,
        String changeReason,

        @Schema(description = "Who the caller says acted; the calling credential itself when nothing was declared.")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String subject,

        @Schema(
                description = "VERIFIED when subject is the validated token's own identity; DECLARED when the"
                        + " caller asserted it, in which case only createdBy is verified.")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String subjectProvenance) {}
