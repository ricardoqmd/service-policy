package io.github.ricardoqmd.servicepolicy.persistence;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
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
 *   <li>{@code policy_heads.(app, policyId)} — unique: at most one head per policy, where a policy
 *       is identified by its application <em>and</em> its id (composite identity, ADR-026). The same
 *       policyId in two applications is two policies.
 *   <li>{@code policy_versions.(app, policyId, version)} — unique: a version is written at most once
 *       (append-only guardrail), independently per application.
 * </ul>
 *
 * <p><strong>Legacy indexes are dropped first (ADR-026).</strong> Before ADR-026 the identity of a
 * policy was {@code policyId} alone, enforced by a unique index on {@code policy_heads.policyId} and
 * one on {@code policy_versions.(policyId, version)}. Those indexes are not merely redundant now —
 * they are <em>wrong</em>: either would reject {@code doc-access} in a second application, which is
 * exactly the bug ADR-026 exists to fix. Creating the new composite indexes does not remove them, so
 * an existing database would keep enforcing the old global uniqueness and the fix would only work on
 * a fresh one. They are therefore dropped explicitly, and their absence (a new database) is not an
 * error.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class PolicyLifecycleIndexes {

    private static final Logger log = Logger.getLogger(PolicyLifecycleIndexes.class);

    /** Mongo's "IndexNotFound" — the index we want gone is already gone (a new database). */
    private static final int INDEX_NOT_FOUND = 27;

    private static final String APP = "app";
    private static final String POLICY_ID = "policyId";
    private static final String VERSION = "version";
    private static final String RESOURCE_TYPE = "resourceType";
    private static final String ACTIVE_VERSION = "activeVersion";

    /** Legacy unique index of the pre-ADR-026 identity: one head per policyId, across all apps. */
    private static final Bson LEGACY_HEAD_IDENTITY = Indexes.ascending(POLICY_ID);

    /** Legacy unique index of the pre-ADR-026 identity: one version per (policyId, version). */
    private static final Bson LEGACY_VERSION_IDENTITY = Indexes.ascending(POLICY_ID, VERSION);

    private final PolicyHeadRepository headRepository;
    private final PolicyVersionRepository versionRepository;

    PolicyLifecycleIndexes(PolicyHeadRepository headRepository, PolicyVersionRepository versionRepository) {
        this.headRepository = headRepository;
        this.versionRepository = versionRepository;
    }

    void ensureIndexes(@Observes StartupEvent event) {
        dropLegacyIdentityIndexes();

        headRepository.mongoCollection().createIndex(Indexes.ascending(APP, RESOURCE_TYPE, ACTIVE_VERSION));
        headRepository
                .mongoCollection()
                .createIndex(Indexes.ascending(APP, POLICY_ID), new IndexOptions().unique(true));
        versionRepository
                .mongoCollection()
                .createIndex(Indexes.ascending(APP, POLICY_ID, VERSION), new IndexOptions().unique(true));
    }

    /**
     * Drops the unique indexes of the pre-ADR-026 identity, which would otherwise keep rejecting the
     * same policyId in a second application. Package-private so the migration can be exercised
     * directly against a database that still carries them.
     */
    void dropLegacyIdentityIndexes() {
        dropIfPresent(headRepository.mongoCollection(), LEGACY_HEAD_IDENTITY);
        dropIfPresent(versionRepository.mongoCollection(), LEGACY_VERSION_IDENTITY);
    }

    private static <T> void dropIfPresent(MongoCollection<T> collection, Bson index) {
        try {
            collection.dropIndex(index);
            log.infof(
                    "dropped legacy pre-ADR-026 unique index %s on '%s'; identity is now (app, policyId)",
                    index, collection.getNamespace().getCollectionName());
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_NOT_FOUND) {
                throw e;
            }
            log.debugf(
                    "no legacy index %s on '%s'; nothing to drop",
                    index, collection.getNamespace().getCollectionName());
        }
    }
}
