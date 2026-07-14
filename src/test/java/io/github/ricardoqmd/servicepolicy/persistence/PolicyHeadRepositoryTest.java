package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Repository-level tests for {@link PolicyHeadRepository#findActiveByAppAndResourceType} and for the
 * composite {@code (app, policyId)} identity lookups of ADR-026.
 */
@QuarkusTest
class PolicyHeadRepositoryTest {

    @Inject
    PolicyHeadRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void returnsOnlyActiveHeadsForTheRequestedAppAndResourceType() {
        repository.persist(head("p-active", "test-app", "document", 1, new Document("key", "val")));
        repository.persist(head("p-inactive", "test-app", "document", null, null));

        List<PolicyHeadDocument> result = repository.findActiveByAppAndResourceType("test-app", "document");

        assertEquals(1, result.size());
        assertEquals("p-active", result.get(0).policyId);
        assertEquals(1, result.get(0).activeVersion);
    }

    @Test
    void doesNotReturnHeadsOfDifferentApp() {
        repository.persist(head("p-other-app", "other-app", "document", 1, new Document("key", "val")));

        List<PolicyHeadDocument> result = repository.findActiveByAppAndResourceType("test-app", "document");

        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotReturnHeadsOfDifferentResourceType() {
        repository.persist(head("p-user", "test-app", "user", 1, new Document("key", "val")));

        List<PolicyHeadDocument> result = repository.findActiveByAppAndResourceType("test-app", "document");

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyListWhenNoActiveHeadsForAppAndType() {
        List<PolicyHeadDocument> result = repository.findActiveByAppAndResourceType("test-app", "document");

        assertTrue(result.isEmpty());
    }

    @Test
    void findsHeadByAppAndPolicyId() {
        repository.persist(head("p-a", "test-app", "document", 1, new Document("key", "val")));

        Optional<PolicyHeadDocument> found = repository.findByAppAndPolicyId("test-app", "p-a");

        assertTrue(found.isPresent());
        assertEquals("p-a", found.get().policyId);
        assertEquals("test-app", found.get().app);
    }

    @Test
    void doesNotFindHeadByPolicyIdOfAnotherApp() {
        repository.persist(head("p-a", "test-app", "document", 1, new Document("key", "val")));

        assertTrue(repository.findByAppAndPolicyId("other-app", "p-a").isEmpty());
        assertFalse(repository.existsByAppAndPolicyId("other-app", "p-a"));
    }

    @Test
    void existsByAppAndPolicyIdReflectsPresence() {
        assertFalse(repository.existsByAppAndPolicyId("test-app", "p-a"));

        repository.persist(head("p-a", "test-app", "document", null, null));

        assertTrue(repository.existsByAppAndPolicyId("test-app", "p-a"));
    }

    /** ADR-026: the same policyId in two apps addresses two independent heads. */
    @Test
    void resolvesTheSamePolicyIdIndependentlyPerApp() {
        repository.persist(head("shared", "test-app", "document", 1, new Document("key", "val")));
        repository.persist(head("shared", "other-app", "user", null, null));

        PolicyHeadDocument inApp =
                repository.findByAppAndPolicyId("test-app", "shared").orElseThrow();
        PolicyHeadDocument inOtherApp =
                repository.findByAppAndPolicyId("other-app", "shared").orElseThrow();

        assertEquals("document", inApp.resourceType);
        assertEquals(1, inApp.activeVersion);
        assertEquals("user", inOtherApp.resourceType);
        assertNull(inOtherApp.activeVersion);
    }

    private static PolicyHeadDocument head(
            String policyId, String app, String resourceType, Integer activeVersion, Document activeContent) {
        PolicyHeadDocument doc = new PolicyHeadDocument();
        doc.policyId = policyId;
        doc.app = app;
        doc.resourceType = resourceType;
        doc.activeVersion = activeVersion;
        doc.activeContent = activeContent;
        doc.revision = 0L;
        doc.audit = new Document("createdBy", "test").append("createdAt", "2026-07-08T00:00:00Z");
        return doc;
    }
}
