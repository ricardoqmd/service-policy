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
 *
 * <p>Two schema markers (ADR-034 §2), because a head holds two shapes. {@code schemaVersion} is the
 * shape of the head itself, written when the head is created and never altered. {@code
 * activeContentSchemaVersion} is the shape of {@code activeContent}, which is copied in from a version:
 * it is copied from that version's marker at the same moment, and rewritten on every activation. A
 * single marker would let a head of one shape hold content of another, which is the inference the
 * marker exists to remove.
 *
 * <p>The content marker lives only while the content does (ADR-034 §9): a new head carries none,
 * deactivation removes it together with the content, and it is only checked when there is content.
 *
 * <p>A head is created by an upsert of a raw document, not from this class (see
 * {@link PolicyLifecycleStore#create}), so its own marker is written there explicitly; the field
 * initialisers below cover only reads and the heads a test persists directly.
 */
@MongoEntity(collection = PolicyHeadDocument.COLLECTION)
public class PolicyHeadDocument {

    static final String COLLECTION = "policy_heads";

    /** The shape of a head this build writes, and the only one it reads. */
    static final int SCHEMA_VERSION = 1;

    public ObjectId id;
    public String policyId;
    public String app;
    public String resourceType;
    public Integer activeVersion; // null when the policy has no active version yet
    public Document activeContent; // null when activeVersion is null
    public long revision;
    public Document audit;

    /**
     * The initialisers are what make an absent marker read as shape 1 (ADR-034 §3): the codec
     * constructs the object and sets only the fields the stored document has.
     */
    public int schemaVersion = SCHEMA_VERSION;

    /**
     * The marker of the version {@code activeContent} was copied from; a version's shape, not a head's.
     * Absent when there is no content; read as 1 when absent beside content stored before the marker.
     */
    public int activeContentSchemaVersion = PolicyVersionDocument.SCHEMA_VERSION;

    public PolicyHeadDocument() {
        // required by the MongoDB POJO codec
    }
}
