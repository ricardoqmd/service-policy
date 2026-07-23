package io.github.ricardoqmd.servicepolicy.enumeration;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Caches computed permission enumerations, keyed by {@code (app, subject)} (ADR-030 §5).
 *
 * <p>The response depends on the subject's derived attributes, the app's active policies, its
 * catalogue and its configuration — so no single stored version is a sound key. The cache therefore
 * holds the <em>computed result</em> and a strong ETag derived from it, and the resource revalidates
 * with {@code If-None-Match}: correct by construction regardless of which input moved, at the cost of
 * one recomputation per revalidation rather than a transfer.
 *
 * <p><strong>Staleness window: {@value #DEFAULT_TTL_SECONDS} seconds.</strong> An entry is served
 * without recomputation until it expires, so a change to policies, catalogue, configuration or the
 * subject's token attributes becomes visible within that bound. This is deliberately acceptable: the
 * response is advisory (ADR-030 §2), and enforcement re-checks every action at {@code /evaluate}, so
 * a stale hint can hide or reveal a control briefly but can never permit anything. There is no
 * write-through invalidation from any store — that, and push invalidation, are the v2 the ADR defers.
 *
 * <p><strong>Bounded.</strong> One entry accrues per distinct subject, which over a long uptime is an
 * unbounded set. Each put first drops entries that have already expired (cheap, and usually enough);
 * if the map still exceeds {@value #MAX_ENTRIES}, it is cleared wholesale. Clearing is crude but safe:
 * the only cost is that the next requests recompute, which is exactly what a cold cache does anyway,
 * and it caps memory without a background sweeper or an eviction policy this advisory cache does not
 * warrant.
 *
 * <p><strong>This class is the seam.</strong> A distributed cache, or one invalidated by a change
 * feed, replaces this bean and nothing else: the resource depends on the {@link #get} contract, not
 * on the map behind it.
 */
// @Singleton (not @ApplicationScoped): the cache is the bean's own state, no proxy needed (ADR-009).
@Singleton
public class PermissionsCache {

    static final long DEFAULT_TTL_SECONDS = 30;
    static final int MAX_ENTRIES = 10_000;

    private final ObjectMapper mapper;
    private final long ttlSeconds;
    private final long ttlNanos;
    private final int maxEntries;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Inject
    PermissionsCache(ObjectMapper mapper) {
        this(mapper, DEFAULT_TTL_SECONDS, MAX_ENTRIES);
    }

    /** Test seam: a tiny TTL and cap make the expiry-eviction and cap-clear arms observable. */
    PermissionsCache(ObjectMapper mapper, long ttlSeconds, int maxEntries) {
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
        this.ttlNanos = Duration.ofSeconds(ttlSeconds).toNanos();
        this.maxEntries = maxEntries;
    }

    /**
     * @return the server-side TTL in seconds, which is also the {@code Cache-Control: max-age} the
     *     response advertises so a client's own cache lifetime tracks the server's staleness window.
     */
    public int maxAgeSeconds() {
        return (int) ttlSeconds;
    }

    /**
     * Returns the cached result for {@code (app, subject)}, computing and storing it on a miss or an
     * expiry. {@code compute} runs only when there is nothing fresh to serve — a cache hit does no
     * enumeration and, therefore, no attribute derivation.
     */
    public CachedPermissions get(String app, String subject, Supplier<List<EnumeratedPair>> compute) {
        Entry entry = cache.get(key(app, subject));
        if (entry != null && !entry.isExpired()) {
            return entry.value();
        }
        List<EnumeratedPair> pairs = compute.get();
        CachedPermissions computed = new CachedPermissions(pairs, Instant.now().toString(), etag(app, subject, pairs));
        store(key(app, subject), computed);
        return computed;
    }

    /** Puts the entry, first evicting expired ones and, if still over the cap, clearing wholesale. */
    private void store(String key, CachedPermissions computed) {
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
        if (cache.size() >= maxEntries) {
            cache.clear();
        }
        cache.put(key, new Entry(computed, System.nanoTime() + ttlNanos));
    }

    private static String key(String app, String subject) {
        return app + '\u001f' + subject;
    }

    /**
     * A strong validator over the app, the subject and the sorted entries — {@code generatedAt}
     * excluded, since it changes every computation and would defeat revalidation.
     *
     * <p>The canonical form is the JSON serialization of {@code (app, subject, entries)}, hashed with
     * SHA-256. JSON rather than a delimiter-joined string on purpose: attribute names (and resource
     * types, actions) are unvalidated free strings, so any chosen joiner could be forged into a value
     * to shift a field boundary and make two different results hash alike. A JSON serializer escapes
     * every value and brackets every array, so {@code dependsOn ["a,b"]} and {@code ["a","b"]} — which
     * a comma-join would conflate — serialize differently and hash differently. The inputs are already
     * canonical (entries sorted by {@code (resourceType, action)}, each {@code dependsOn} sorted) and
     * the serializer is deterministic, so the same result always yields the same ETag.
     */
    String etag(String app, String subject, List<EnumeratedPair> pairs) {
        byte[] canonical;
        try {
            canonical = mapper.writeValueAsBytes(new CanonicalForm(app, subject, pairs));
        } catch (JsonProcessingException e) {
            // These are plain records of strings/booleans/lists; serialization cannot fail here.
            throw new IllegalStateException("failed to serialize the ETag canonical form", e);
        }
        return '"' + sha256Hex(canonical) + '"';
    }

    private static String sha256Hex(byte[] input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JCA algorithm; its absence is a broken JVM, not a runtime case.
            throw new IllegalStateException("SHA-256 not available", e);
        }
        byte[] hash = digest.digest(input);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** The exact structure hashed for the ETag; a record so Jackson serializes its fields in order. */
    private record CanonicalForm(String app, String subject, List<EnumeratedPair> entries) {}

    private record Entry(CachedPermissions value, long expiresAtNanos) {

        boolean isExpired() {
            // Subtraction, not comparison: correct across the nanoTime wraparound.
            return System.nanoTime() - expiresAtNanos >= 0;
        }
    }

    // ── test seam ────────────────────────────────────────────────────────────

    /** @return the current number of cached entries (package-private, for the eviction/cap tests). */
    int size() {
        return cache.size();
    }
}
