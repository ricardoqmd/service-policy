package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests the caching contract of {@link AppConfigProvider} (ADR-029 §reading it is cached).
 *
 * <p>Cache hits are proven the only way an in-memory cache can be proven from outside: by changing
 * the database behind its back and observing that the provider does not notice. A read that still
 * returns the superseded answer is a read that never went to Mongo — which is the property the
 * evaluation path will depend on, and the reason absence is cached too.
 */
@QuarkusTest
class AppConfigProviderTest {

    private static final String APP = "provider-test-app";

    @Inject
    AppConfigProvider provider;

    @Inject
    AppConfigStore store;

    @Inject
    AppConfigRepository repository;

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
        provider.invalidate(APP);
    }

    /**
     * The case that matters most: an app with no configuration. Most apps are in this state, so if
     * absence were a cache miss the common path would hit Mongo on every decision — the opposite of
     * what the cache exists for.
     */
    @Test
    void absenceIsCachedNotJustMissing() {
        assertTrue(provider.forApp(APP).isEmpty());

        // Insert behind the provider's back — no invalidation, so the cached "absent" must stand.
        repository.persist(document(APP, 1L));

        assertTrue(
                provider.forApp(APP).isEmpty(),
                "a cached absence must be served without re-reading Mongo within the TTL");

        provider.invalidate(APP);
        assertTrue(provider.forApp(APP).isPresent(), "after invalidation the provider must see the new document");
    }

    @Test
    void aPresentConfigurationIsCached() {
        repository.persist(document(APP, 1L));

        AppConfig first = provider.forApp(APP).orElseThrow();
        assertEquals(1L, first.revision());
        assertEquals("realm_access.roles", first.subjectAttributes().get("rol"));
        assertEquals("https://backend/subjects/{sub}", first.pip().url());
        assertEquals(500, first.pip().timeoutMs());
        assertEquals(300, first.pip().cacheTtlSeconds());
        assertEquals("cred-ref", first.pip().credentialRef());

        // Replace the document underneath the cache; the cached copy must survive.
        repository.deleteAll();
        repository.persist(document(APP, 99L));

        assertEquals(1L, provider.forApp(APP).orElseThrow().revision());
    }

    /** Write-through invalidation: a create through the store is visible to the very next read. */
    @Test
    void createThroughTheStoreInvalidatesImmediately() {
        assertTrue(provider.forApp(APP).isEmpty()); // warm the cache with an absence

        store.create(APP, draft(), "tester");

        AppConfig loaded = provider.forApp(APP).orElseThrow();
        assertEquals(1L, loaded.revision());
        assertEquals("realm_access.roles", loaded.subjectAttributes().get("rol"));
    }

    @Test
    void replaceThroughTheStoreInvalidatesImmediately() {
        store.create(APP, draft(), "tester");
        assertEquals(1L, provider.forApp(APP).orElseThrow().revision());

        store.replace(APP, new AppConfigDraft(Map.of("dept", "department"), null), 1L, "tester");

        AppConfig loaded = provider.forApp(APP).orElseThrow();
        assertEquals(2L, loaded.revision());
        assertEquals("department", loaded.subjectAttributes().get("dept"));
        assertNull(loaded.pip(), "a replace that omits 'pip' removes it");
    }

    @Test
    void deleteThroughTheStoreInvalidatesImmediately() {
        store.create(APP, draft(), "tester");
        assertTrue(provider.forApp(APP).isPresent());

        store.delete(APP, 1L);

        assertTrue(provider.forApp(APP).isEmpty());
    }

    /** Cache entries are per app: invalidating one must not disturb another's. */
    @Test
    void invalidationIsScopedToItsApp() {
        store.create(APP, draft(), "tester");
        store.create("other-provider-app", draft(), "tester");
        assertTrue(provider.forApp(APP).isPresent());
        assertTrue(provider.forApp("other-provider-app").isPresent());

        store.delete("other-provider-app", 1L);

        assertTrue(provider.forApp(APP).isPresent());
        assertTrue(provider.forApp("other-provider-app").isEmpty());
        provider.invalidate("other-provider-app");
    }

    /** A stored document with only one section maps back with the other left null, not empty. */
    @Test
    void anAbsentSectionStaysNull() {
        store.create(APP, new AppConfigDraft(Map.of("rol", "realm_access.roles"), null), "tester");

        Optional<AppConfig> loaded = provider.forApp(APP);
        assertTrue(loaded.isPresent());
        assertNull(loaded.get().pip());
        assertEquals(1, loaded.get().subjectAttributes().size());
    }

    /**
     * The cached path must survive out-of-band data exactly as the administrative path does: a stored
     * mapping with a null claim path is dropped, not propagated into a map that would then blow up on
     * copy. One fewer derivable attribute can never widen a decision (ADR-011).
     */
    @Test
    void aStoredNullClaimPathIsDroppedRatherThanFailingTheRead() {
        AppConfigDocument seeded = document(APP, 1L);
        seeded.subjectAttributes = new Document("rol", "realm_access.roles").append("broken", null);
        repository.persist(seeded);

        AppConfig loaded = provider.forApp(APP).orElseThrow();
        assertEquals(Map.of("rol", "realm_access.roles"), loaded.subjectAttributes());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static AppConfigDraft draft() {
        return new AppConfigDraft(
                Map.of("rol", "realm_access.roles"),
                new AppConfigDraft.PipDraft("https://backend/subjects/{sub}", 500, 300, "cred-ref"));
    }

    /** Seeded straight through the repository, so the provider's cache is never told about it. */
    private static AppConfigDocument document(String app, long revision) {
        AppConfigDocument document = new AppConfigDocument();
        document.app = app;
        document.subjectAttributes = new Document("rol", "realm_access.roles");
        document.pip = new Document("url", "https://backend/subjects/{sub}")
                .append("timeoutMs", 500)
                .append("cacheTtlSeconds", 300)
                .append("credentialRef", "cred-ref");
        document.revision = revision;
        document.audit = new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
        return document;
    }
}
