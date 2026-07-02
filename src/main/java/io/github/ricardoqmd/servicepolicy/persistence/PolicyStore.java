package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;

import jakarta.inject.Singleton;

import org.bson.Document;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;

/**
 * Bridges the MongoDB repository and the pure domain: loads/saves domain {@link Policy} objects
 * by delegating shape conversion to {@link PolicyDocumentMapper}.
 *
 * <p>The mappers are stateless and framework-free, so they are constructed here rather than
 * injected — keeping them free of CDI annotations.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed; the
// @ApplicationScoped proxy is instantiated without calling the constructor, which
// hides constructor/field-initializer coverage from JaCoCo (see PR #14).
@Singleton
public class PolicyStore {

    private final PolicyRepository repository;
    private final PolicyDocumentMapper mapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    public PolicyStore(PolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the active policies governing the given resource type, as domain objects.
     */
    public List<Policy> activePoliciesFor(String resourceType) {
        return repository.findActiveByResourceType(resourceType).stream()
                .map(document -> mapper.fromDocument(document.content))
                .toList();
    }

    /**
     * @return {@code true} if a policy with the given id already exists (any version or state).
     */
    public boolean exists(String policyId) {
        return repository.existsByPolicyId(policyId);
    }

    /** Persists a policy with the given active flag. */
    public void save(Policy policy, boolean active) {
        repository.persist(new PolicyDocument(active, new Document(mapper.toDocument(policy))));
    }
}
