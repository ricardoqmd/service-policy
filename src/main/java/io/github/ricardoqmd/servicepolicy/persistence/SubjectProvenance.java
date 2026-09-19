package io.github.ricardoqmd.servicepolicy.persistence;

/**
 * Where the acting identity recorded by a control-plane write came from (ADR-033 §4).
 *
 * <p>One token attests one identity. What this engine validates is {@link #VERIFIED}; anything a caller
 * says about a person behind it is {@link #DECLARED} — an assertion by the calling credential, never an
 * attestation. The record keeps the two apart so that it is never believed about the wrong person.
 */
public enum SubjectProvenance {
    /** The acting identity is the subject of the validated token itself. */
    VERIFIED,
    /** The acting identity was named by the caller; only the calling credential is verified. */
    DECLARED
}
