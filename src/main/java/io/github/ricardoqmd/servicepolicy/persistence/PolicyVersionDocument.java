package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.Document;
import org.bson.types.ObjectId;

import io.quarkus.mongodb.panache.common.MongoEntity;

/**
 * MongoDB document for an immutable, append-only policy version (ADR-016). Written once, never
 * mutated. {@code content} is the storage-agnostic content shape produced by
 * {@link PolicyDocumentMapper}. The {@code (app, policyId, version)} triple is unique (enforced by a
 * unique compound index, see {@link PolicyLifecycleIndexes}), which structurally forbids writing the
 * same version twice while letting two applications version the same policy id independently
 * (composite identity, ADR-026).
 *
 * <p>{@code app} is stored on the version too — it is part of the identity, and {@code content} no
 * longer carries it (the server takes it from the path, ADR-026).
 *
 * <p>{@code schemaVersion} names the shape this document — {@code content} included — was written in
 * (ADR-034). It is not {@code version}, which is the policy's. Written once with the document and never
 * altered, like everything else here. A head that copies {@code content} copies this marker with it.
 */
@MongoEntity(collection = PolicyVersionDocument.COLLECTION)
public class PolicyVersionDocument {

    static final String COLLECTION = "policy_versions";

    /** The shape this build writes, and the only one it reads. */
    static final int SCHEMA_VERSION = 1;

    public ObjectId id;
    public String app;
    public String policyId;
    public int version;
    public Document content;
    public Document audit;

    /**
     * Set here, at construction, so every document this build inserts carries it. It is also what
     * makes an absent marker read as shape 1 (ADR-034 §3): the codec constructs the object and sets
     * only the fields the stored document has, so a document written before the marker existed keeps
     * this value.
     */
    public int schemaVersion = SCHEMA_VERSION;

    public PolicyVersionDocument() {
        // required by the MongoDB POJO codec
    }
}
