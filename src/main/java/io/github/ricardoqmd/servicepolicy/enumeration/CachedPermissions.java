package io.github.ricardoqmd.servicepolicy.enumeration;

import java.util.List;

/**
 * One computed enumeration result, held by {@link PermissionsCache} so a single computation serves
 * both the {@code 200} and any subsequent {@code 304} (ADR-030 §5).
 *
 * <p>It carries the enumeration-package pairs, not the REST view — the boundary maps them — so the
 * cache stays on the enumeration side of the isolation. {@code generatedAt} is stored, not recomputed
 * on read: it must stay stable across a revalidation, and it is excluded from {@code etag} for the
 * same reason (a timestamp that moved every request would break {@code If-None-Match}).
 *
 * @param pairs       the included pairs, already sorted and canonical.
 * @param generatedAt RFC 3339 UTC instant of the computation.
 * @param etag        strong validator over {@code (app, subject, pairs)} — see {@link PermissionsCache}.
 */
public record CachedPermissions(List<EnumeratedPair> pairs, String generatedAt, String etag) {

    public CachedPermissions {
        pairs = List.copyOf(pairs);
    }
}
