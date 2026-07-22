package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.Document;
import org.bson.types.ObjectId;

import io.quarkus.mongodb.panache.common.MongoEntity;

/**
 * MongoDB document for one application's configuration (ADR-029): the claim-to-attribute mapping and
 * the attribute-source (PIP) settings that later ADRs consume.
 *
 * <p>A singleton per application — one document, keyed by {@code app} alone, with a unique index (see
 * {@link AppConfigIndexes}) that both enforces the singleton and arbitrates a duplicate create.
 * Configuration is not a collection: there is nothing to list within an app.
 *
 * <p>Both sections are nullable and independently optional: an app may configure claim mapping
 * without an attribute source, or the reverse. Absence is meaningful and is preserved as {@code
 * null} rather than flattened to an empty object, because "not configured" and "configured empty"
 * are different answers for the future readers of this document.
 *
 * <p>{@code pip} holds a {@code credentialRef} — a reference, never credential material. ADR-029 is
 * explicit that a collection administrators can read must not become a place where secrets live.
 *
 * <p>{@code revision} is the ETag the conditional writes CAS against (ADR-018), starting at 1: a
 * configuration is created with content, so there is no empty revision-0 state to distinguish.
 */
@MongoEntity(collection = "app_configs")
public class AppConfigDocument {

    public ObjectId id;
    public String app;
    public Document subjectAttributes; // null when the app configures no claim mapping
    public Document pip; // null when the app configures no attribute source
    public long revision;
    public Document audit;

    public AppConfigDocument() {
        // required by the MongoDB POJO codec
    }
}
