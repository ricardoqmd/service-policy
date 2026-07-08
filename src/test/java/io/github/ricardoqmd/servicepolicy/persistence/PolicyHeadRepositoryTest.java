package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/** Repository-level tests for {@link PolicyHeadRepository#findActiveByResourceType}. */
@QuarkusTest
class PolicyHeadRepositoryTest {

    @Inject
    PolicyHeadRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void returnsOnlyActiveHeadsForTheRequestedResourceType() {
        repository.persist(head("p-active", "document", 1, new Document("key", "val")));
        repository.persist(head("p-inactive", "document", null, null));

        List<PolicyHeadDocument> result = repository.findActiveByResourceType("document");

        assertEquals(1, result.size());
        assertEquals("p-active", result.get(0).policyId);
        assertEquals(1, result.get(0).activeVersion);
    }

    @Test
    void doesNotReturnHeadsOfDifferentResourceType() {
        repository.persist(head("p-user", "user", 1, new Document("key", "val")));

        List<PolicyHeadDocument> result = repository.findActiveByResourceType("document");

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyListWhenNoActiveHeadsForType() {
        List<PolicyHeadDocument> result = repository.findActiveByResourceType("document");

        assertTrue(result.isEmpty());
    }

    private static PolicyHeadDocument head(
            String policyId, String resourceType, Integer activeVersion, Document activeContent) {
        PolicyHeadDocument doc = new PolicyHeadDocument();
        doc.policyId = policyId;
        doc.resourceType = resourceType;
        doc.activeVersion = activeVersion;
        doc.activeContent = activeContent;
        doc.revision = 0L;
        doc.audit = new Document("createdBy", "test").append("createdAt", "2026-07-08T00:00:00Z");
        return doc;
    }
}
