package io.github.ricardoqmd.servicepolicy.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Error response body for 4xx responses.
 *
 * @param error   Machine-readable error code (e.g. {@code BAD_REQUEST}, {@code UNAUTHORIZED}).
 * @param message Human-readable description of what went wrong.
 */
@Schema(description = "Error response body.")
public record ApiError(
        @Schema(required = true, description = "Machine-readable error code.")
        String error,

        @Schema(required = true, description = "Human-readable description of the error.")
        String message) {}
