package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

/** Repository for {@link PolicyHeadDocument} (ADR-016). */
@ApplicationScoped
public class PolicyHeadRepository implements PanacheMongoRepository<PolicyHeadDocument> {

    private static final String ACTIVE_HEADS = "{'activeVersion': {$ne: null}}";

    /**
     * @return active heads (those with a non-null {@code activeVersion}), ordered by policyId, for
     *     the requested zero-based page.
     */
    public List<PolicyHeadDocument> findActiveHeads(int pageIndex, int size) {
        return find(ACTIVE_HEADS, Sort.ascending("policyId"))
                .page(Page.of(pageIndex, size))
                .list();
    }

    /** @return the number of active heads. */
    public long countActiveHeads() {
        return count(ACTIVE_HEADS);
    }

    /** @return the head for the given policyId, if present. */
    public Optional<PolicyHeadDocument> findByPolicyId(String policyId) {
        return find("{'policyId': ?1}", policyId).firstResultOptional();
    }

    /** @return {@code true} if a head exists for the given policyId. */
    public boolean existsByPolicyId(String policyId) {
        return count("{'policyId': ?1}", policyId) > 0;
    }

    /**
     * @return all active heads (non-null {@code activeVersion}) for the given resource type.
     *     Returns the complete set — not paged — because the evaluator needs every candidate.
     */
    public List<PolicyHeadDocument> findActiveByResourceType(String resourceType) {
        return find("{'activeVersion': {$ne: null}, 'resourceType': ?1}", resourceType)
                .list();
    }
}
