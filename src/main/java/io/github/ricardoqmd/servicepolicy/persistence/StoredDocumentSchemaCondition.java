package io.github.ricardoqmd.servicepolicy.persistence;

import org.bson.BsonType;
import org.bson.conversions.Bson;

import com.mongodb.client.model.Filters;

/**
 * The clause every write to an existing stored document carries, so that a build does not write to a
 * document whose shape it does not recognise (ADR-034 §8). It goes <em>inside</em> the write's filter,
 * next to the identity and the {@code If-Match} revision, so the store decides the condition and applies
 * the write in one operation: there is no window between a check and the write for the document to
 * change shape in.
 *
 * <p>It admits these two cases and nothing else — in particular nothing the read guard refuses (ADR-034
 * §11):
 *
 * <ul>
 *   <li>the known marker as an <em>integer</em>, 32- or 64-bit (ADR-034 §10). Equality alone is not
 *       enough, for two reasons of query semantics: the store compares numbers across types, so a
 *       decimal or a floating-point {@code 1} is equal to it; and equality on an array field matches an
 *       array that <em>contains</em> the value. The type restriction removes the first. It does not remove
 *       the second — a type test on an array field also matches when one of its elements has the type — so
 *       arrays are excluded explicitly;
 *   <li>an absent marker, which is shape 1 (ADR-034 §3): every document stored before the marker existed
 *       has none, and must stay writable without being backfilled. An explicit {@code null} is not
 *       absence, and is not admitted.
 * </ul>
 *
 * <p>The read is deliberately more tolerant than this for a floating-point or decimal {@code 1} that the
 * codec converts exactly: such a malformed marker leaves a document readable and not writable, the
 * direction ADR-034 §11 permits.
 *
 * <p>A document this clause refuses fails the write the way a stale revision does, and the caller's
 * existing re-read — through the document type's read guard — decides what to answer.
 */
final class StoredDocumentSchemaCondition {

    private StoredDocumentSchemaCondition() {
        // static helper
    }

    /**
     * @param field the document's marker field.
     * @param known the one marker value this build writes and reads for the document type.
     * @return a filter matching a document whose marker is {@code known} as an integer, or absent.
     */
    static Bson writable(String field, int known) {
        return Filters.or(
                Filters.and(
                        Filters.eq(field, known),
                        Filters.or(Filters.type(field, BsonType.INT32), Filters.type(field, BsonType.INT64)),
                        Filters.not(Filters.type(field, BsonType.ARRAY))),
                Filters.exists(field, false));
    }
}
