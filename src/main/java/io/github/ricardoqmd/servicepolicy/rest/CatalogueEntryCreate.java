package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

/**
 * Request body of {@code POST /v1/apps/&#123;app&#125;/action-catalogue} (ADR-028).
 *
 * <p>A typed record rather than a free map, so request bodies stay strict: any field that is not
 * declared here — {@code app} above all (ADR-026) — is rejected with 400 {@code BAD_REQUEST} by
 * {@link UnknownPropertyMapper} instead of being silently discarded.
 *
 * @param resourceType the resource type whose vocabulary is being declared; required, non-blank.
 * @param actions      the action tokens that exist for it; required, non-empty, no blanks, no
 *                     duplicates, and never the literal {@code "*"} — the catalogue is the explicit
 *                     set that {@code "*"} expands to, so it cannot contain it.
 */
public record CatalogueEntryCreate(String resourceType, List<String> actions) {}
