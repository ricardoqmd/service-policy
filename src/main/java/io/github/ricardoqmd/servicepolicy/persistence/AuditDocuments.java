package io.github.ricardoqmd.servicepolicy.persistence;

import java.time.Instant;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.client.model.Filters;

/**
 * The one shape of the audit sub-document every stored write carries (ADR-014, ADR-033 §4), shared by the
 * policy, catalogue and configuration stores so the three cannot record an actor three different ways.
 */
final class AuditDocuments {

    static final String CREATED_BY = "createdBy";
    static final String CREATED_AT = "createdAt";
    static final String CHANGE_REASON = "changeReason";
    static final String SUBJECT = "subject";
    static final String SUBJECT_PROVENANCE = "subjectProvenance";

    private AuditDocuments() {
        // static helper
    }

    /**
     * @return the audit of a write by {@code actor}: the credential, the subject and its provenance, always
     *     all three. A document seeded by installation carries them too, with the installation identity
     *     (ADR-033 §4). The two newer fields are omitted only for an actor that carries no provenance, which
     *     nothing in this build constructs.
     */
    static Document of(AuditActor actor) {
        Document document = new Document(CREATED_BY, actor.credential())
                .append(CREATED_AT, Instant.now().toString());
        if (actor.provenance() != null) {
            document.append(SUBJECT, actor.subject())
                    .append(SUBJECT_PROVENANCE, actor.provenance().name());
        }
        return document;
    }

    /**
     * @return a filter matching the documents whose audit records {@code actor} in all three fields at once.
     *     Any one of them alone is not enough: a caller may <em>declare</em> any subject, which records it
     *     with its own credential and {@code DECLARED}.
     */
    static Bson writtenBy(AuditActor actor) {
        return Filters.and(
                Filters.eq("audit." + CREATED_BY, actor.credential()),
                Filters.eq("audit." + SUBJECT, actor.subject()),
                Filters.eq("audit." + SUBJECT_PROVENANCE, actor.provenance().name()));
    }

    /** @return the provenance stored in {@code audit}, or {@code null} when absent or not one this build knows. */
    static SubjectProvenance provenanceOf(Document audit) {
        String stored = audit.getString(SUBJECT_PROVENANCE);
        if (stored == null) {
            return null;
        }
        for (SubjectProvenance provenance : SubjectProvenance.values()) {
            if (provenance.name().equals(stored)) {
                return provenance;
            }
        }
        return null;
    }
}
