package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.types.ObjectId;

/**
 * Thrown when a stored document carries a schema marker this build does not recognise (ADR-034 §5).
 *
 * <p>Deliberately <strong>not</strong> a {@code ProblemException} and handled by no
 * {@code ExceptionMapper}: it surfaces as the framework's default {@code 500}, the way this service
 * already treats failures the caller cannot act on (ADR-034 §6). It has no public error code — the
 * caller did nothing wrong, and there is nothing in its request it could change.
 *
 * <p>The message names the collection, the document id, the marker field and the value found, and
 * nothing else: a document whose shape is unknown is exactly the one whose content must not be echoed
 * into a log or a response.
 */
public class StoredDocumentSchemaException extends RuntimeException {

    public StoredDocumentSchemaException(String collection, ObjectId id, String marker, int found) {
        super("stored document " + collection + "/" + id + " carries " + marker + " " + found
                + ", which this build does not recognise");
    }
}
