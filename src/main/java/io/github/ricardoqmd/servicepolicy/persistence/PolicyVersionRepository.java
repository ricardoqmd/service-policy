package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

/** Repository for {@link PolicyVersionDocument} (ADR-016). */
@ApplicationScoped
public class PolicyVersionRepository implements PanacheMongoRepository<PolicyVersionDocument> {

    private static final String IDENTITY = "{'app': ?1, 'policyId': ?2}";

    /**
     * @return versions of the given policy — identified by {@code (app, policyId)} (ADR-026) —
     *     newest first, for the requested zero-based page.
     */
    public List<PolicyVersionDocument> findByAppAndPolicyId(String app, String policyId, int pageIndex, int size) {
        return find(IDENTITY, Sort.descending("version"), app, policyId)
                .page(Page.of(pageIndex, size))
                .list();
    }

    /** @return the number of versions of the given policy. */
    public long countByAppAndPolicyId(String app, String policyId) {
        return count(IDENTITY, app, policyId);
    }

    /** @return the specific version of the given policy, if present. */
    public Optional<PolicyVersionDocument> findByAppAndPolicyIdAndVersion(String app, String policyId, int version) {
        return find("{'app': ?1, 'policyId': ?2, 'version': ?3}", app, policyId, version)
                .firstResultOptional();
    }
}
