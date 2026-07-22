package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.Map;

/**
 * An unvalidated configuration document on its way in (ADR-029): what a caller asked for, before
 * {@link AppConfigValidator} has had a say.
 *
 * <p>Separate from the {@link AppConfig} read model on purpose. A draft's numeric fields are boxed
 * so that "absent" is representable — required-ness is a validation verdict, not a parse outcome —
 * whereas the read model has primitives because by the time a document is stored every field is
 * present and in range. Keeping the two apart is also what lets the store own validation without
 * the persistence layer importing the web layer's request records.
 */
public record AppConfigDraft(Map<String, String> subjectAttributes, PipDraft pip) {

    /** The {@code pip} section of a draft; see {@link PipConfig} for what each field means. */
    public record PipDraft(String url, Integer timeoutMs, Integer cacheTtlSeconds, String credentialRef) {}
}
