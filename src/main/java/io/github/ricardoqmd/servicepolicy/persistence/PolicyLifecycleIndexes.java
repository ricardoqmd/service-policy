package io.github.ricardoqmd.servicepolicy.persistence;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import io.quarkus.runtime.StartupEvent;

/**
 * Ensures the MongoDB indexes for the head-pointer model (ADR-016) at startup. {@code createIndex}
 * is idempotent, so running this on every boot is safe.
 *
 * <ul>
 *   <li>{@code policy_heads.(app, resourceType, activeVersion)} — evaluation reads by app and
 *       resource type (ADR-024); non-unique.
 *   <li>{@code policy_heads.policyId} — unique: at most one head per policy (structural invariant).
 *   <li>{@code policy_versions.(policyId, version)} — unique: a version is written at most once
 *       (append-only guardrail).
 * </ul>
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class PolicyLifecycleIndexes {

    private final PolicyHeadRepository headRepository;
    private final PolicyVersionRepository versionRepository;

    PolicyLifecycleIndexes(PolicyHeadRepository headRepository, PolicyVersionRepository versionRepository) {
        this.headRepository = headRepository;
        this.versionRepository = versionRepository;
    }

    void ensureIndexes(@Observes StartupEvent event) {
        headRepository.mongoCollection().createIndex(Indexes.ascending("app", "resourceType", "activeVersion"));
        headRepository.mongoCollection().createIndex(Indexes.ascending("policyId"), new IndexOptions().unique(true));
        versionRepository
                .mongoCollection()
                .createIndex(Indexes.ascending("policyId", "version"), new IndexOptions().unique(true));
    }
}
