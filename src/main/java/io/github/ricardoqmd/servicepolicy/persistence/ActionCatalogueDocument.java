package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;

import io.quarkus.mongodb.panache.common.MongoEntity;

/**
 * MongoDB document for one action catalogue entry (ADR-028): the set of action tokens that exist
 * for a resource type in an application, one document per {@code (app, resourceType)}.
 *
 * <p>Actions are opaque, stable identifiers (ADR-005): the catalogue holds ids, not display text.
 * Uniqueness of {@code (app, resourceType)} is enforced by a unique index (see
 * {@link ActionCatalogueIndexes}), which is also what arbitrates a duplicate create — the same
 * duplicate-key pattern the policy write path uses, rather than a read-then-write pre-check.
 *
 * <p>{@code revision} is the ETag the conditional writes CAS against, exactly as on a policy head
 * (ADR-018), and starts at 1 — an entry is created with content, so there is no "empty" revision 0
 * state to distinguish.
 *
 * <p>{@code schemaVersion} names the shape the entry was written in (ADR-034). It is written on
 * creation and no replace touches it: a replace sets the action list, never the shape it sits in.
 */
@MongoEntity(collection = ActionCatalogueDocument.COLLECTION)
public class ActionCatalogueDocument {

    static final String COLLECTION = "action_catalogue";

    /** The shape this build writes, and the only one it reads. */
    static final int SCHEMA_VERSION = 1;

    public ObjectId id;
    public String app;
    public String resourceType;
    public List<String> actions;
    public long revision;
    public Document audit;

    /**
     * Set at construction, so every document this build inserts carries it; and what makes an absent
     * marker read as shape 1 (ADR-034 §3), because the codec sets only the fields the stored document
     * has.
     */
    public int schemaVersion = SCHEMA_VERSION;

    public ActionCatalogueDocument() {
        // required by the MongoDB POJO codec
    }
}
