package io.github.ricardoqmd.servicepolicy;

import java.util.List;

import org.bson.Document;

import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueDocument;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;

/**
 * Arrange-phase helper for the action catalogue (ADR-028).
 *
 * <p>Authoring validates {@code actions} against the catalogue of {@code (app, resourceType)}, so a
 * test that creates, appends or simulates a policy has to declare that vocabulary first — otherwise
 * every write fails with 400 for a reason the test is not about. Seeding goes through the repository
 * rather than the admin endpoint on purpose: the catalogue REST surface has its own suite, and
 * driving it here would make unrelated tests depend on it.
 *
 * <p>{@link #declare} is idempotent (it clears the entry first) because the collection outlives a
 * single test class, and the unique {@code (app, resourceType)} index would otherwise turn a
 * leftover from an earlier class into a duplicate-key failure here.
 */
public final class ActionCatalogueTestSupport {

    private ActionCatalogueTestSupport() {
        // static helper
    }

    /** Declares {@code actions} as the vocabulary of {@code (app, resourceType)}, replacing any entry. */
    public static void declare(
            ActionCatalogueRepository repository, String app, String resourceType, String... actions) {
        repository.delete("{'app': ?1, 'resourceType': ?2}", app, resourceType);

        ActionCatalogueDocument document = new ActionCatalogueDocument();
        document.app = app;
        document.resourceType = resourceType;
        document.actions = List.of(actions);
        document.revision = 1L;
        document.audit = new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
        repository.persist(document);
    }
}
