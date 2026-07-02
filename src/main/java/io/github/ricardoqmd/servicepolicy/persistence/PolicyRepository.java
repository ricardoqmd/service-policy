package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.mongodb.panache.PanacheMongoRepository;

/** Repository for {@link PolicyDocument}. */
@ApplicationScoped
public class PolicyRepository implements PanacheMongoRepository<PolicyDocument> {

    /**
     * @return the active policy documents governing the given resource type.
     */
    public List<PolicyDocument> findActiveByResourceType(String resourceType) {
        return list("{'active': ?1, 'content.resourceType': ?2}", true, resourceType);
    }

    /**
     * @return {@code true} if any policy document already has the given policy id.
     */
    public boolean existsByPolicyId(String policyId) {
        return count("{'content.policyId': ?1}", policyId) > 0;
    }
}
