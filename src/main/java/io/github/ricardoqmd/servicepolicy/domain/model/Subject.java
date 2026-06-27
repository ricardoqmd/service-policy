package io.github.ricardoqmd.servicepolicy.domain.model;

import java.util.Map;

/**
 * The subject of an authorization request.
 *
 * <p>{@code id} is always present (resolved from the JWT {@code sub}). {@code attributes}
 * is an open bag sourced from JWT claims (e.g. {@code role}); the engine presupposes no
 * specific attribute. Organization-specific attributes (e.g. an area) enter the bag if
 * and when an upstream system provides them — the engine stays agnostic.
 *
 * @param id         stable subject identifier.
 * @param attributes open attribute bag from JWT claims; never null.
 */
public record Subject(String id, Map<String, Object> attributes) {

    public Subject {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
