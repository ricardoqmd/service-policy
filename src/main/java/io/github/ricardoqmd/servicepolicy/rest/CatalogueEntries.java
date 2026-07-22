package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

/**
 * Envelope of the action catalogue listing (ADR-028): {@code {"data": [...]}}.
 *
 * <p>The {@code data} key is this API's envelope convention for a collection — {@link Paginated}
 * already ships {@code {data, pagination}} — and an unpaginated collection keeps it rather than
 * inventing a name of its own. What it does not carry is the {@code pagination} block: a catalogue
 * is a bounded vocabulary, deliberately returned whole (see {@code ActionCatalogueResource#list}),
 * so there is no page to describe. Enveloping it anyway keeps the response an object rather than a
 * bare array, leaving room to add members later without breaking clients.
 */
public record CatalogueEntries(List<CatalogueEntryView> data) {}
