package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.Map;

/**
 * Read model of one application's configuration (ADR-029), the store↔REST boundary type — the same
 * role {@link ActionCatalogueEntry} plays for the action catalogue, keeping Mongo documents out of
 * the web layer.
 *
 * @param subjectAttributes attribute name → claim path, or {@code null} when the app configures no
 *                          claim mapping. Null and empty are not the same thing here: null is "no
 *                          such section", which is why it is not normalised away.
 * @param pip               attribute-source settings, or {@code null} when none are configured.
 * @param revision          current revision; the value carried by the response's ETag.
 */
public record AppConfig(String app, Map<String, String> subjectAttributes, PipConfig pip, long revision) {

    public AppConfig {
        subjectAttributes = subjectAttributes == null ? null : Map.copyOf(subjectAttributes);
    }
}
