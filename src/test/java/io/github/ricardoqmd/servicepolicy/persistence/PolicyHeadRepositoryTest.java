package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/** Repository-level tests for {@link PolicyHeadRepository#findActiveByAppAndResourceType}. */
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
