package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

/**
 * Envelope of the action catalogue listing (ADR-028): {@code {"entries": [...]}}.
 *
 * <p>Not a {@link Paginated}, deliberately — see {@code ActionCatalogueResource#list}. The envelope
 * exists anyway so the response is an object rather than a bare array, leaving room to add members
 * later without breaking clients.
 */
public record CatalogueEntries(List<CatalogueEntryView> entries) {}
