package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.Document;
import org.bson.types.ObjectId;

import io.quarkus.mongodb.panache.common.MongoEntity;

/**
 * MongoDB document for a stored policy (repository pattern: a plain POJO, not active-record).
 *
 * <p>Persistence-only concerns live here: the Mongo {@code _id} and the {@code active} flag.
 * The policy content itself is the storage-agnostic shape produced by
 * {@link PolicyDocumentMapper}, kept as a generic {@link Document} so the domain stays free of
 * persistence types.
 */
@MongoEntity(collection = "policies")
public class PolicyDocument {

    public ObjectId id;
    public boolean active;
    public Document content;

    public PolicyDocument() {
        // required by the MongoDB POJO codec
    }

    public PolicyDocument(boolean active, Document content) {
        this.active = active;
        this.content = content;
    }
}
