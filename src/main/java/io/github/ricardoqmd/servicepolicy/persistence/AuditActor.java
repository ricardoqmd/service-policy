package io.github.ricardoqmd.servicepolicy.persistence;

/**
 * Who a write is recorded against (ADR-014 audit metadata, ADR-033 §4): on every write a caller makes, all
 * three facts together.
 *
 * @param credential the identity of the validated token that made the call. Stored as {@code createdBy},
 *     whose meaning is unchanged.
 * @param subject    who the caller says acted; the credential itself when nothing was declared.
 * @param provenance whether {@code subject} was verified by this engine or declared by the caller.
 */
public record AuditActor(String credential, String subject, SubjectProvenance provenance) {

    /** The name installation writes under. It is no caller's credential; it is the installation itself. */
    public static final String INSTALLATION = "service-policy:installation";

    /** A write whose acting identity is the calling credential itself. */
    public static AuditActor verified(String credential) {
        return new AuditActor(credential, credential, SubjectProvenance.VERIFIED);
    }

    /**
     * A document seeded by installation (ADR-033 §4, §6). All three fields carry the installation identity,
     * with provenance {@link SubjectProvenance#VERIFIED}: the write was made by this engine, not asserted
     * by a caller, and recording it that way spares every consumer a special case for an absent subject.
     */
    public static AuditActor installation() {
        return new AuditActor(INSTALLATION, INSTALLATION, SubjectProvenance.VERIFIED);
    }

    /**
     * Applies the declaration a write may carry. A declaration confers no authority (ADR-033 §4): it only
     * changes what the record says, so absent, blank or equal to the credential is the credential itself.
     */
    public static AuditActor declaring(String credential, String declaredSubject) {
        if (declaredSubject == null || declaredSubject.isBlank() || declaredSubject.equals(credential)) {
            return verified(credential);
        }
        return new AuditActor(credential, declaredSubject, SubjectProvenance.DECLARED);
    }
}
