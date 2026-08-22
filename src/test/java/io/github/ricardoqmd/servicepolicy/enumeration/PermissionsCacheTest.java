package io.github.ricardoqmd.servicepolicy.enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests for {@link PermissionsCache}: the collision-proof ETag, per-subject keying (a security
 * property — one subject must never read another's cached view), and the bounding arms (expired
 * entries evicted on put, and a wholesale clear at the cap).
 *
 * <p>A {@code @QuarkusTest} so it runs under the coverage agent and can inject the managed
 * {@link ObjectMapper}; the bounding arms use the package-private test constructor with a tiny TTL
 * and cap, which is the only way to make eviction and the cap observable without waiting or inserting
 * ten thousand entries.
 */
@QuarkusTest
class PermissionsCacheTest {

    @Inject
    ObjectMapper mapper;

    @Inject
    PermissionsCache cache; // the real singleton (30 s TTL, 10_000 cap)

    /** The digest of an empty bag — what both transports pass when nothing resolved. */
    private String noAttrs() {
        return cache.digest(Map.of());
    }

    /**
     * The forged-delimiter case (ADR-030 audit): {@code dependsOn ["a,b"]} and {@code ["a","b"]} are
     * different results a comma-join would hash alike. The JSON canonical form keeps them apart.
     */
    @Test
    void etagIsCollisionProofAgainstForgedDelimiters() {
        List<EnumeratedPair> oneName = List.of(new EnumeratedPair("document", "update", true, List.of("a,b")));
        List<EnumeratedPair> twoNames = List.of(new EnumeratedPair("document", "update", true, List.of("a", "b")));

        assertNotEquals(cache.etag("app", "sub", noAttrs(), oneName), cache.etag("app", "sub", noAttrs(), twoNames));
    }

    /** The same result always hashes to the same ETag (deterministic canonical form + serializer). */
    @Test
    void etagIsStableForTheSameResult() {
        List<EnumeratedPair> pairs = List.of(new EnumeratedPair("document", "update", true, List.of("areaId")));

        assertEquals(cache.etag("app", "sub", noAttrs(), pairs), cache.etag("app", "sub", noAttrs(), pairs));
    }

    /**
     * The security-consequence property: the cache is keyed by {@code (app, subject)}, so a second
     * subject's request against the same app computes its own result and never receives the first
     * subject's cached view.
     */
    @Test
    void oneSubjectNeverReceivesAnothersCachedView() {
        String app = "cache-isolation-app";
        CachedPermissions alice = cache.get(app, "alice", Map.of(), () -> List.of(pair("create")));

        boolean[] bobComputed = {false};
        CachedPermissions bob = cache.get(app, "bob", Map.of(), () -> {
            bobComputed[0] = true;
            return List.of(pair("delete"));
        });

        assertTrue(bobComputed[0], "bob's request must compute its own result, not read alice's cached one");
        assertNotEquals(alice.etag(), bob.etag());
        assertEquals("create", alice.pairs().get(0).action());
        assertEquals("delete", bob.pairs().get(0).action());
    }

    /**
     * With a zero TTL every entry is born expired: a re-get recomputes (the expiry arm of {@code get})
     * and a put for another key evicts the expired entry (the eviction arm of {@code store}).
     */
    @Test
    void expiredEntriesAreEvictedOnPut() {
        PermissionsCache tiny = new PermissionsCache(mapper, 0, PermissionsCache.MAX_ENTRIES);

        tiny.get("app", "alice", Map.of(), () -> List.of(pair("a")));
        assertEquals(1, tiny.size());

        boolean[] recomputed = {false};
        tiny.get("app", "alice", Map.of(), () -> {
            recomputed[0] = true;
            return List.of(pair("a"));
        });
        assertTrue(recomputed[0], "an expired entry is recomputed, not served");

        tiny.get("app", "bob", Map.of(), () -> List.of(pair("b")));
        assertEquals(1, tiny.size(), "the expired alice entry was evicted when bob's entry was put");
    }

    /** When the cap is reached, the cache is cleared wholesale before the new entry is stored. */
    @Test
    void reachingTheCapClearsTheCache() {
        PermissionsCache capped = new PermissionsCache(mapper, PermissionsCache.DEFAULT_TTL_SECONDS, 2);

        capped.get("app", "a", Map.of(), () -> List.of(pair("x")));
        capped.get("app", "b", Map.of(), () -> List.of(pair("x")));
        assertEquals(2, capped.size());

        capped.get("app", "c", Map.of(), () -> List.of(pair("x")));
        assertEquals(1, capped.size(), "reaching the cap clears the map, then stores only the new entry");
    }

    // ── The attribute digest (ADR-032 §4) ────────────────────────────────────

    /**
     * The defect this fix closes: the same {@code (app, subject)} under two attribute sets is two
     * different menus. Before the digest entered the key, the second caller was served the first
     * caller's result.
     */
    @Test
    void twoAttributeSetsForOneSubjectDoNotShareAnEntry() {
        String app = "cache-attrs-app";
        CachedPermissions north = cache.get(app, "alice", Map.of("area", "north"), () -> List.of(pair("create")));

        boolean[] recomputed = {false};
        CachedPermissions south = cache.get(app, "alice", Map.of("area", "south"), () -> {
            recomputed[0] = true;
            return List.of(pair("delete"));
        });

        assertTrue(recomputed[0], "a different attribute set must compute its own result");
        assertNotEquals(north.etag(), south.etag(), "and must not share the first one's ETag");
        assertEquals("create", north.pairs().get(0).action());
        assertEquals("delete", south.pairs().get(0).action());
    }

    /** Same attributes in a different insertion order are the same bag, so the same entry. */
    @Test
    void keyOrderDoesNotChangeTheDigest() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("area", "north");
        one.put("contractId", "c-1");
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("contractId", "c-1");
        other.put("area", "north");

        assertEquals(cache.digest(one), cache.digest(other));
    }

    /** Ordering is applied at every level, not only the top one. */
    @Test
    void nestedKeyOrderDoesNotChangeTheDigestEither() {
        Map<String, Object> nestedOne = new LinkedHashMap<>();
        nestedOne.put("a", 1);
        nestedOne.put("b", 2);
        Map<String, Object> nestedOther = new LinkedHashMap<>();
        nestedOther.put("b", 2);
        nestedOther.put("a", 1);

        assertEquals(cache.digest(Map.of("scope", nestedOne)), cache.digest(Map.of("scope", nestedOther)));
    }

    /**
     * The forged-delimiter case for attributes, the reason the bag is hashed as JSON rather than
     * joined: a caller choosing the single key {@code "area\u001fnorth"} must not collide with the
     * pair {@code area}/{@code north} that a unit-separator join would produce.
     */
    @Test
    void theDigestIsCollisionProofAgainstForgedDelimiters() {
        assertNotEquals(cache.digest(Map.of("area\u001fnorth", "")), cache.digest(Map.of("area", "north")));
        assertNotEquals(cache.digest(Map.of("a", "b:c")), cache.digest(Map.of("a", "b", "c", "")));
    }

    /** A missing bag and an empty bag are the same absence, and neither may throw. */
    @Test
    void aNullBagDigestsAsAnEmptyOne() {
        assertEquals(cache.digest(Map.of()), cache.digest(null));
    }

    /** The digest reaches the ETag too — the half that outlives the TTL. */
    @Test
    void theEtagChangesWithTheAttributes() {
        List<EnumeratedPair> pairs = List.of(pair("create"));

        assertNotEquals(
                cache.etag("app", "sub", cache.digest(Map.of("area", "north")), pairs),
                cache.etag("app", "sub", cache.digest(Map.of("area", "south")), pairs));
    }

    private static EnumeratedPair pair(String action) {
        return new EnumeratedPair("document", action, false, List.of());
    }
}
