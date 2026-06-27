package io.github.ricardoqmd.servicepolicy.domain.model;

import java.util.Map;

/**
 * The resource targeted by an authorization request.
 *
 * <p>Pure-domain mirror of the wire {@code ResourceRef}. {@code attributes} is an open
 * bag supplied by the PEP (e.g. {@code assignees}, {@code sealed}); the engine
 * presupposes no specific attribute.
 *
 * @param type       resource type, e.g. {@code document}.
 * @param id         resource instance id; may be null for collection-level checks.
 * @param attributes open attribute bag; never null.
 */
public record Resource(String type, String id, Map<String, Object> attributes) {

    public Resource {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
