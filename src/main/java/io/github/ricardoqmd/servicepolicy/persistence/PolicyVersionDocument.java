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
 */
@MongoEntity(collection = "policy_versions")
public class PolicyVersionDocument {

    public ObjectId id;
    public String app;
    public String policyId;
    public int version;
    public Document content;
    public Document audit;

    public PolicyVersionDocument() {
        // required by the MongoDB POJO codec
    }
}
