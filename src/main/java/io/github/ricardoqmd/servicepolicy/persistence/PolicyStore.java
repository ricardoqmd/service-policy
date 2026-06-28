package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.bson.Document;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;

/**
 * Bridges the MongoDB repository and the pure domain: loads/saves domain {@link Policy} objects
 * by delegating shape conversion to {@link PolicyDocumentMapper}.
 *
 * <p>The mappers are stateless and framework-free, so they are constructed here rather than
 * injected — keeping them free of CDI annotations.
 */
@ApplicationScoped
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

    /** Persists a policy with the given active flag. */
    public void save(Policy policy, boolean active) {
        repository.persist(new PolicyDocument(active, new Document(mapper.toDocument(policy))));
    }
}
