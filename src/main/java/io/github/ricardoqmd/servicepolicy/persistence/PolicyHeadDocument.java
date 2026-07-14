package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.Document;
import org.bson.types.ObjectId;

import io.quarkus.mongodb.panache.common.MongoEntity;

/**
 * MongoDB document for a policy head (ADR-016): one document per {@code policyId} carrying the
 * activation pointer and a denormalized copy of the active version's content.
 *
 * <p>Persistence-only concerns live here. {@code activeContent} is the storage-agnostic content
 * shape produced by {@link PolicyDocumentMapper}, kept as a generic {@link Document} so the domain
 * stays free of persistence types; it is {@code null} until the policy has an active version.
 *
 * <p>Uniqueness of the composite identity {@code (app, policyId)} is enforced by a unique index (see
 * {@link PolicyLifecycleIndexes}), which gives the same "at most one head per policy" guarantee as a
 * natural key without changing the repository's {@link ObjectId} convention used across this
 * codebase. {@code app} is the scoping coordinate and part of the identity (ADR-026): the server
 * takes it from the path and it is not present in {@code activeContent}.
 */
@MongoEntity(collection = "policy_heads")
public class PolicyHeadDocument {

    public ObjectId id;
    public String policyId;
    public String app;
    public String resourceType;
    public Integer activeVersion; // null when the policy has no active version yet
    public Document activeContent; // null when activeVersion is null
    public long revision;
    public Document audit;

    public PolicyHeadDocument() {
        // required by the MongoDB POJO codec
    }
}
