package io.github.ricardoqmd.servicepolicy.rest.problem;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ProblemExceptionMapper implements ExceptionMapper<ProblemException> {

    static final String PROBLEM_JSON = "application/problem+json";

    @Override
    public Response toResponse(ProblemException exception) {
        return Response.status(exception.getStatus())
                .type(PROBLEM_JSON)
                .entity(exception.toProblemDetail())
                .build();
    }
}
