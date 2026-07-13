package io.github.ricardoqmd.servicepolicy.domain.model;

import java.util.Map;

/**
 * Pure-domain authorization request the policy engine evaluates.
 *
 * <p>Free of transport concerns. The REST layer maps the wire DTO
 * ({@code EvaluationRequest}) plus the JWT-resolved subject into this type, so the
 * engine never depends on HTTP/OpenAPI types.
 *
 * @param app      application scope for this request (ADR-024); selects which policies are candidates.
 * @param subject  who is asking (id + attribute bag from JWT claims).
 * @param action   action in {@code type:verb} format, e.g. {@code document:read}.
 * @param resource what is being accessed.
 * @param context  circumstance attributes, e.g. {@code {"emergency": true}}; never null.
 */
public record AuthorizationRequest(
        String app, Subject subject, String action, Resource resource, Map<String, Object> context) {

    public AuthorizationRequest {
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
