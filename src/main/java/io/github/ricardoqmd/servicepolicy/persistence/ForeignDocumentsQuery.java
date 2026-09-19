package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.SortedSet;
import java.util.TreeSet;

import jakarta.inject.Singleton;

import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

/**
 * Finds, in one application, every document installation did not write (ADR-033 §6): configuration,
 * catalogue entries, policy heads and policy versions.
 *
 * <p>"Installation wrote it" is decided by the audit it records on its own documents, all three fields at
 * once (ADR-033 §4) — never by what a document says, because a document that merely resembles the baseline
 * is still not one this installation wrote. The collections are read as raw documents, so a document of a
 * shape this build does not know is still found and named rather than failing the read.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class ForeignDocumentsQuery {

    private final PolicyHeadRepository headRepository;

    ForeignDocumentsQuery(PolicyHeadRepository headRepository) {
        this.headRepository = headRepository;
    }

    /** @return what {@code app} holds that installation did not write; {@link ForeignDocuments#none} if nothing. */
    public ForeignDocuments in(String app) {
        Bson foreign =
                Filters.and(Filters.eq("app", app), Filters.nor(AuditDocuments.writtenBy(AuditActor.installation())));
        MongoDatabase database = headRepository.mongoDatabase();

        boolean configuration =
                collection(database, AppConfigDocument.COLLECTION).countDocuments(foreign) > 0;
        SortedSet<String> catalogueEntries =
                distinct(collection(database, ActionCatalogueDocument.COLLECTION), "resourceType", foreign);
        SortedSet<String> policies = distinct(collection(database, PolicyHeadDocument.COLLECTION), "policyId", foreign);
        policies.addAll(distinct(collection(database, PolicyVersionDocument.COLLECTION), "policyId", foreign));
        return new ForeignDocuments(configuration, catalogueEntries, policies);
    }

    private static MongoCollection<Document> collection(MongoDatabase database, String name) {
        return database.getCollection(name);
    }

    /**
     * Identifiers as stored. One that is not a string is rendered rather than refused, and a document that has
     * none is named as such, so every foreign document is counted even where it cannot be identified.
     */
    private static SortedSet<String> distinct(MongoCollection<Document> collection, String field, Bson filter) {
        SortedSet<String> values = new TreeSet<>();
        for (BsonValue value : collection.distinct(field, filter, BsonValue.class)) {
            values.add(value instanceof BsonString string ? string.getValue() : String.valueOf(value));
        }
        if (collection.countDocuments(Filters.and(filter, Filters.exists(field, false))) > 0) {
            values.add("(a document without " + field + ")");
        }
        return values;
    }
}
