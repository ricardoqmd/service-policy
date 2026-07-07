package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

/**
 * Mediates between the head-pointer repositories (ADR-016) and the read models, keeping the REST
 * layer off Panache. This is the seam that will host the write invariants (append-only versioning,
 * head-first idempotent create, single-document activation, optimistic concurrency) in later
 * slices; in this slice it exposes reads only.
 *
 * <p>Introduced alongside the legacy {@link PolicyStore}, which keeps serving the evaluator from the
 * {@code policies} collection until the activation slice repoints it (ADR-016).
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class PolicyLifecycleStore {

    private final PolicyHeadRepository headRepository;
    private final PolicyVersionRepository versionRepository;
    private final PolicyLifecycleDocumentMapper mapper =
            new PolicyLifecycleDocumentMapper(new PolicyDocumentMapper(new ConditionDocumentMapper()));

    PolicyLifecycleStore(PolicyHeadRepository headRepository, PolicyVersionRepository versionRepository) {
        this.headRepository = headRepository;
        this.versionRepository = versionRepository;
    }

    /** @return active policy heads for the requested zero-based page. */
    public List<PolicyHead> findActiveHeads(int pageIndex, int size) {
        return headRepository.findActiveHeads(pageIndex, size).stream()
                .map(mapper::head)
                .toList();
    }

    /** @return the number of active policy heads. */
    public long countActiveHeads() {
        return headRepository.countActiveHeads();
    }

    /** @return the head for the given policyId, if present. */
    public Optional<PolicyHead> findHead(String policyId) {
        return headRepository.findByPolicyId(policyId).map(mapper::head);
    }

    /** @return {@code true} if a head exists for the given policyId. */
    public boolean headExists(String policyId) {
        return headRepository.existsByPolicyId(policyId);
    }

    /** @return versions of the given policy (newest first) for the requested zero-based page. */
    public List<PolicyVersion> findVersions(String policyId, int pageIndex, int size) {
        return versionRepository.findByPolicyId(policyId, pageIndex, size).stream()
                .map(mapper::version)
                .toList();
    }

    /** @return the number of versions of the given policy. */
    public long countVersions(String policyId) {
        return versionRepository.countByPolicyId(policyId);
    }

    /** @return the specific version of the given policy, if present. */
    public Optional<PolicyVersion> findVersion(String policyId, int version) {
        return versionRepository.findByPolicyIdAndVersion(policyId, version).map(mapper::version);
    }
}
