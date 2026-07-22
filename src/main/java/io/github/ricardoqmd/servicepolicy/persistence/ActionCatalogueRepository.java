package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.panache.common.Sort;

/** Repository for {@link ActionCatalogueDocument} (ADR-028). */
@ApplicationScoped
public class ActionCatalogueRepository implements PanacheMongoRepository<ActionCatalogueDocument> {

    private static final String IDENTITY = "{'app': ?1, 'resourceType': ?2}";
    private static final String APP_CLAUSE = "{'app': ?1}";
    private static final Sort BY_RESOURCE_TYPE = Sort.ascending("resourceType");

    /** @return the entry for the composite identity {@code (app, resourceType)}, if present. */
    public Optional<ActionCatalogueDocument> findByAppAndResourceType(String app, String resourceType) {
        return find(IDENTITY, app, resourceType).firstResultOptional();
    }

    /**
     * @return every catalogue entry of the app, ordered by resource type. Deliberately not paged: a
     *     catalogue is a bounded vocabulary, and its consumers need it whole (ADR-028).
     */
    public List<ActionCatalogueDocument> findByApp(String app) {
        return find(APP_CLAUSE, BY_RESOURCE_TYPE, app).list();
    }
}
