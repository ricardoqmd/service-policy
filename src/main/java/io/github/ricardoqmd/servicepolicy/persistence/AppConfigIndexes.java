package io.github.ricardoqmd.servicepolicy.persistence;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import io.quarkus.runtime.StartupEvent;

/**
 * Ensures the MongoDB index for per-application configuration (ADR-029) at startup.
 * {@code createIndex} is idempotent, so running this on every boot is safe.
 *
 * <ul>
 *   <li>{@code app_configs.app} — unique: an application has at most one configuration document.
 *       Like the action catalogue's index (ADR-028) this is not merely an optimisation — it is what
 *       arbitrates a duplicate create into {@code APP_CONFIG_ALREADY_EXISTS}, so two concurrent
 *       creates cannot both succeed and leave the app with two configurations.
 * </ul>
 *
 * <p>Nothing legacy to drop: the collection arrives with ADR-029 and has never had another identity.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class AppConfigIndexes {

    private static final String APP = "app";

    private final AppConfigRepository repository;

    AppConfigIndexes(AppConfigRepository repository) {
        this.repository = repository;
    }

    void ensureIndexes(@Observes StartupEvent event) {
        repository.mongoCollection().createIndex(Indexes.ascending(APP), new IndexOptions().unique(true));
    }
}
