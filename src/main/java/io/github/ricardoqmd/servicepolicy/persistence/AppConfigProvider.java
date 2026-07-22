package io.github.ricardoqmd.servicepolicy.persistence;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Singleton;

/**
 * Cached read port for per-application configuration (ADR-029 §reading it is cached).
 *
 * <p><strong>Nothing consumes this yet.</strong> Its consumers are the claim-to-attribute mapping
 * and the PIP adapter, each with its own ADR; ADR-029 stops at making configuration administrable.
 * It is built now because the caching contract is part of that decision, and because a cache added
 * later, under the pressure of a hot path, is a cache designed under pressure. It is deliberately
 * not wired into any evaluation path in this change.
 *
 * <p><strong>Absence is cached too.</strong> Most applications will have no configuration for a
 * while, and the read that must not touch Mongo per decision is precisely theirs. Caching only
 * present values would leave the common case uncached — the opposite of the point.
 *
 * <p><strong>Staleness contract.</strong> Writes through {@link AppConfigStore} invalidate this
 * cache, so on a single-instance deployment — what this engine runs as today — a change is visible
 * to the next read. Should it ever run multi-instance, other instances keep serving their cached
 * copy until the entry expires: the {@value #TTL_SECONDS}-second TTL is the documented staleness
 * window ADR-029 requires, and it is the upper bound on how long a decision can be made against
 * superseded configuration. That is acceptable because configuration is additive capability — a
 * stale copy resolves fewer or different attributes, and under deny-overrides with
 * {@code defaultEffect: DENY} that can only produce equal or stricter outcomes (ADR-011). No change
 * stream, no polling: both would buy convergence this deployment shape does not need.
 */
// @Singleton (not @ApplicationScoped): the cache is the bean's own state, no proxy needed (ADR-009).
@Singleton
public class AppConfigProvider {

    static final long TTL_SECONDS = 60;

    private static final long TTL_NANOS = Duration.ofSeconds(TTL_SECONDS).toNanos();

    private final AppConfigRepository repository;
    private final Map<String, CachedConfig> cache = new ConcurrentHashMap<>();

    AppConfigProvider(AppConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the app's configuration, or empty when it has none — served from cache while the entry
     *     is fresh. An empty result is a cached answer, not a cache miss.
     */
    public Optional<AppConfig> forApp(String app) {
        CachedConfig cached = cache.get(app);
        if (cached != null && !cached.isExpired()) {
            return Optional.ofNullable(cached.value());
        }
        AppConfig loaded =
                repository.findByApp(app).map(AppConfigMapper::toAppConfig).orElse(null);
        cache.put(app, CachedConfig.of(loaded));
        return Optional.ofNullable(loaded);
    }

    /**
     * Drops the app's cached entry so the next read reloads. Called by {@link AppConfigStore} after
     * every successful write, which is what makes same-instance writes visible immediately.
     */
    public void invalidate(String app) {
        cache.remove(app);
    }

    /** @param value the configuration, or {@code null} to remember that the app has none. */
    private record CachedConfig(AppConfig value, long expiresAtNanos) {

        static CachedConfig of(AppConfig value) {
            return new CachedConfig(value, System.nanoTime() + TTL_NANOS);
        }

        boolean isExpired() {
            // Subtraction, not comparison: it stays correct across the nanoTime wraparound.
            return System.nanoTime() - expiresAtNanos >= 0;
        }
    }
}
