package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of a {@link PermissionsView}: a {@code (resourceType, action)} pair the caller can act on
 * (ADR-030 §3). Structured, not a {@code "resource:action"} string — a client that wants a string can
 * build one, but a client handed a string cannot reliably split it.
 *
 * @param conditional {@code false} when the permit holds for every instance; {@code true} when it
 *                    depends on the resource, in which case the client must ask per instance at
 *                    {@code /evaluate} before acting.
 * @param dependsOn   the resource attribute names to send in that {@code /evaluate} request
 *                    (ADR-030 §3.1). Omitted from the JSON when empty — which it always is for an
 *                    unconditional entry — so its presence itself signals conditionality.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record PermissionEntry(String resourceType, String action, boolean conditional, List<String> dependsOn) {}
