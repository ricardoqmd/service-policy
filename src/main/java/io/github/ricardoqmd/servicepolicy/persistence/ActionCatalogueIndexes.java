package io.github.ricardoqmd.servicepolicy.persistence;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import io.quarkus.runtime.StartupEvent;

/**
 * Ensures the MongoDB index for the action catalogue (ADR-028) at startup. {@code createIndex} is
 * idempotent, so running this on every boot is safe.
 *
 * <ul>
 *   <li>{@code action_catalogue.(app, resourceType)} — unique: an application declares the
 *       vocabulary of a resource type at most once. This index is not merely an optimisation — it is
 *       what arbitrates a duplicate create into {@code CATALOGUE_ENTRY_ALREADY_EXISTS}, the same way
 *       the policy version index arbitrates {@code POLICY_ALREADY_EXISTS} (ADR-019).
 * </ul>
 *
 * <p>Unlike {@link PolicyLifecycleIndexes} there is no legacy index to drop: the collection is new
 * with ADR-028 and has never had a different identity.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class ActionCatalogueIndexes {

    private static final String APP = "app";
    private static final String RESOURCE_TYPE = "resourceType";

    private final ActionCatalogueRepository repository;

    ActionCatalogueIndexes(ActionCatalogueRepository repository) {
        this.repository = repository;
    }

    void ensureIndexes(@Observes StartupEvent event) {
        repository
                .mongoCollection()
                .createIndex(Indexes.ascending(APP, RESOURCE_TYPE), new IndexOptions().unique(true));
    }
}
