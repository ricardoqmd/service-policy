package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Reference to the resource being accessed.
 *
 * <p>Attributes are optional and may be supplied inline by the PEP. They are keyed
 * by attribute id or code per ADR-005.
 *
 * @param type       Resource type (e.g. {@code empleado}, {@code permiso}).
 * @param id         Unique identifier of the resource instance. May be null for collection-level checks.
 * @param attributes Optional inline resource attributes (e.g. {@code {"confidencial": true}}).
 */
@Schema(description = "Target resource being accessed.")
public record ResourceRef(
        @Schema(required = true, description = "Resource type, e.g. 'empleado'.")
        String type,

        @Schema(description = "Unique resource identifier. Null for collection-level checks.")
        String id,

        @Schema(description = "Optional inline resource attributes, keyed by attribute id or code.")
        Map<String, Object> attributes) {}
