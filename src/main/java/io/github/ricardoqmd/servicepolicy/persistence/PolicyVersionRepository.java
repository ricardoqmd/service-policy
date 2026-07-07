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

    /**
     * @return versions of the given policy, newest first, for the requested zero-based page.
     */
    public List<PolicyVersionDocument> findByPolicyId(String policyId, int pageIndex, int size) {
        return find("{'policyId': ?1}", Sort.descending("version"), policyId)
                .page(Page.of(pageIndex, size))
                .list();
    }

    /** @return the number of versions of the given policy. */
    public long countByPolicyId(String policyId) {
        return count("{'policyId': ?1}", policyId);
    }

    /** @return the specific version of the given policy, if present. */
    public Optional<PolicyVersionDocument> findByPolicyIdAndVersion(String policyId, int version) {
        return find("{'policyId': ?1, 'version': ?2}", policyId, version).firstResultOptional();
    }
}
