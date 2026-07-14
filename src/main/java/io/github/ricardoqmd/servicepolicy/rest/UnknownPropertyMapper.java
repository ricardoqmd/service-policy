package io.github.ricardoqmd.servicepolicy.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;

/**
 * Turns an unknown field in a typed JSON request body into a 400 {@code BAD_REQUEST} problem+json,
 * instead of the framework's untyped 400 (no error code) — reusing the existing catalogue, no new
 * code (ADR-026).
 *
 * <p>Request bodies are strict (see {@code quarkus.jackson.fail-on-unknown-properties}). The field
 * this exists for is {@code app}: ADR-026 removed it from every request body, so a body that still
 * carries it must be rejected rather than silently ignored — otherwise a client could state one app
 * in the route and another in the payload and never learn that the payload was discarded. The
 * message names {@code app} explicitly, because a stale client sending it is the expected case.
 */
@Provider
public class UnknownPropertyMapper implements ExceptionMapper<UnrecognizedPropertyException> {

    private static final String APP = "app";

    @Override
    public Response toResponse(UnrecognizedPropertyException exception) {
        String field = exception.getPropertyName();
        InvalidRequestException problem = new InvalidRequestException(
                APP.equals(field)
                        ? "'app' must not be present in the body; it is determined by the path."
                        : "unknown field '" + field + "' in request body.");
        return Response.status(problem.getStatus())
                .type(ProblemExceptionMapper.PROBLEM_JSON)
                .entity(problem.toProblemDetail())
                .build();
    }
}
