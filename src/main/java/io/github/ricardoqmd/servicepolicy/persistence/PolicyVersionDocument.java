package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.Document;
import org.bson.types.ObjectId;

import io.quarkus.mongodb.panache.common.MongoEntity;

/**
 * MongoDB document for an immutable, append-only policy version (ADR-016). Written once, never
 * mutated. {@code content} is the storage-agnostic content shape produced by
 * {@link PolicyDocumentMapper}. The {@code (policyId, version)} pair is unique (enforced by a unique
 * compound index, see {@link PolicyLifecycleIndexes}), which structurally forbids writing the same
 * version twice.
 */
@MongoEntity(collection = "policy_versions")
public class PolicyVersionDocument {

    public ObjectId id;
    public String policyId;
    public int version;
    public Document content;
    public Document audit;

    public PolicyVersionDocument() {
        // required by the MongoDB POJO codec
    }
}
