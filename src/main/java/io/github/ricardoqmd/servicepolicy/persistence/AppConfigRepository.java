package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.mongodb.panache.PanacheMongoRepository;

/** Repository for {@link AppConfigDocument} (ADR-029). */
@ApplicationScoped
public class AppConfigRepository implements PanacheMongoRepository<AppConfigDocument> {

    private static final String IDENTITY = "{'app': ?1}";

    /**
     * @return the app's configuration, if it has one. There is no list counterpart: configuration is
     *     a singleton per application, so {@code app} alone is the whole identity.
     */
    public Optional<AppConfigDocument> findByApp(String app) {
        return find(IDENTITY, app).firstResultOptional();
    }
}
