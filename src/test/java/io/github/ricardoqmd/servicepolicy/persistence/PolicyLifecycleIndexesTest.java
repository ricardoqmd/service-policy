package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Migration test for the index rebuild of ADR-026.
 *
 * <p>The composite identity {@code (app, policyId)} is only actually in force once the pre-ADR-026
 * unique indexes are gone. Creating the new composite indexes does not remove the old ones, so on a
 * database that already ran ADR-024 the legacy {@code policyId} index would keep rejecting the same
 * id in a second application — the fix would work on a fresh database and silently fail on a real
 * one. This test reproduces that database: it re-creates the legacy indexes, runs the startup
 * routine, and asserts both that they are gone and that the behaviour they used to block now works.
 */
@QuarkusTest
class PolicyLifecycleIndexesTest {

    private static final String LEGACY_HEAD_INDEX = "policyId_1";
    private static final String LEGACY_VERSION_INDEX = "policyId_1_version_1";

    @Inject
    PolicyLifecycleIndexes indexes;

    @Inject
    PolicyLifecycleStore store;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
    }

    @AfterEach
    void restoreIndexes() {
        // Leave the collections in the state the application boots into, whatever this test did.
        indexes.ensureIndexes(null);
    }

    @Test
    void startupDropsTheLegacyIdentityIndexesAndFreesThePolicyIdPerApp() {
        givenADatabaseWithThePreAdr026Indexes();
        assertTrue(headIndexNames().contains(LEGACY_HEAD_INDEX), "precondition: the legacy head index exists");
        assertTrue(versionIndexNames().contains(LEGACY_VERSION_INDEX), "precondition: the legacy version index exists");

        indexes.ensureIndexes(null);

        assertFalse(headIndexNames().contains(LEGACY_HEAD_INDEX), "the legacy unique index on policyId must be gone");
        assertFalse(
                versionIndexNames().contains(LEGACY_VERSION_INDEX),
                "the legacy unique index on (policyId, version) must be gone");
        assertTrue(headIndexNames().contains("app_1_policyId_1"), "the composite head index must exist");
        assertTrue(
                versionIndexNames().contains("app_1_policyId_1_version_1"), "the composite version index must exist");

        // The behaviour the legacy indexes blocked: the same id, and the same version number, in two apps.
        store.create("nami", policy("doc-access"), "tester", null);
        store.create("kronia", policy("doc-access"), "tester", null);

        assertTrue(store.headExists("nami", "doc-access"));
        assertTrue(store.headExists("kronia", "doc-access"));
        assertEquals(1, store.countVersions("nami", "doc-access"));
        assertEquals(1, store.countVersions("kronia", "doc-access"));
    }

    /** Running on a database that never had the legacy indexes must not fail (a fresh install). */
    @Test
    void startupIsANoOpWhenTheLegacyIndexesAreAbsent() {
        indexes.ensureIndexes(null); // drops them
        indexes.ensureIndexes(null); // nothing left to drop — must not throw

        assertFalse(headIndexNames().contains(LEGACY_HEAD_INDEX));
        assertTrue(headIndexNames().contains("app_1_policyId_1"));
    }

    private void givenADatabaseWithThePreAdr026Indexes() {
        headRepository.mongoCollection().createIndex(Indexes.ascending("policyId"), new IndexOptions().unique(true));
        versionRepository
                .mongoCollection()
                .createIndex(Indexes.ascending("policyId", "version"), new IndexOptions().unique(true));
    }

    private List<String> headIndexNames() {
        return indexNames(headRepository.mongoCollection().listIndexes());
    }

    private List<String> versionIndexNames() {
        return indexNames(versionRepository.mongoCollection().listIndexes());
    }

    private static List<String> indexNames(Iterable<Document> listing) {
        List<String> names = new ArrayList<>();
        for (Document index : listing) {
            names.add(index.getString("name"));
        }
        return names;
    }

    private static Policy policy(String id) {
        return new Policy(
                id, 1, "document", List.of("read"), CombiningAlgorithm.DENY_OVERRIDES, Effect.DENY, List.of());
    }
}
