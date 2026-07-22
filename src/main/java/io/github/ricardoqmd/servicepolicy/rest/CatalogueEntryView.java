package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

/**
 * Response shape of one action catalogue entry (ADR-028).
 *
 * <p>Unlike the request bodies, this one does carry {@code app}: the server supplies it from the
 * path, and responses have always echoed the scope they were read under (ADR-026) — the asymmetry
 * is the point, since only a client-sent {@code app} could contradict the route.
 *
 * @param revision the entry's current revision, mirrored in the response's strong ETag.
 */
public record CatalogueEntryView(String app, String resourceType, List<String> actions, long revision) {}
