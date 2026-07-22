package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;

/**
 * Read model of one action catalogue entry (ADR-028), keyed by {@code (app, resourceType)}.
 *
 * <p>Mirrors {@link PolicyHead}: the REST layer consumes this record rather than the Mongo document,
 * so persistence types never cross the boundary and the store stays the only door to the collection.
 *
 * @param app          owning application; a path coordinate (ADR-026), never a body field.
 * @param resourceType resource type whose vocabulary this entry declares.
 * @param actions      the action tokens that exist for that resource type, in declaration order —
 *                     which is also the order {@code ["*"]} expands to at authoring.
 * @param revision     current revision; the value carried by the entry's ETag.
 */
public record ActionCatalogueEntry(String app, String resourceType, List<String> actions, long revision) {

    public ActionCatalogueEntry {
        actions = List.copyOf(actions);
    }
}
