package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.evaluation.BatchEvaluationRequest;
import io.github.ricardoqmd.servicepolicy.evaluation.BatchEvaluationResult;
import io.github.ricardoqmd.servicepolicy.evaluation.Decision;
import io.github.ricardoqmd.servicepolicy.evaluation.EvaluationRequest;
import io.github.ricardoqmd.servicepolicy.evaluation.PolicyEvaluator;

/**
 * PEP-facing authorization evaluation endpoints.
 *
 * <p>Accepts evaluation requests from Policy Enforcement Points (PEPs) and returns
 * {@link Decision} responses from the configured {@link PolicyEvaluator}.
 *
 * <p>Subject is always resolved from the {@code Authorization: Bearer <jwt>} header.
 * It is never accepted from the request body.
 */
@Path("/v1/evaluate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "evaluation", description = "PEP-facing authorization evaluation endpoints.")
public class EvaluateResource {

    @Inject
    PolicyEvaluator evaluator;

    @Inject
    SubjectResolver subjectResolver;

    @POST
    @Operation(
            summary = "Evaluate a single authorization request",
            description = "Evaluates whether the authenticated subject may perform the requested action on the"
                    + " given resource. Subject is resolved from the Bearer JWT in the Authorization"
                    + " header. Returns HTTP 401 if the header is missing or malformed. Returns HTTP"
                    + " 400 if 'action' or 'resource.type' is blank.")
    public Response evaluate(@HeaderParam("Authorization") String authorization, EvaluationRequest request) {
        String subject = subjectResolver.resolve(authorization);
        if (request == null || request.action() == null || request.action().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("BAD_REQUEST", "action must not be blank."))
                    .build();
        }
        if (request.resource() == null
                || request.resource().type() == null
                || request.resource().type().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("BAD_REQUEST", "resource.type must not be blank."))
                    .build();
        }
        return Response.ok(evaluator.evaluate(subject, request)).build();
    }

    @POST
    @Path("/batch")
    @Operation(
            summary = "Evaluate a batch of authorization requests",
            description = "Evaluates multiple requests in a single call. Results are returned in the same order"
                    + " as the input requests. Requires a valid Bearer token in the Authorization"
                    + " header.")
    public Response batch(@HeaderParam("Authorization") String authorization, BatchEvaluationRequest batchRequest) {
        String subject = subjectResolver.resolve(authorization);
        List<Decision> decisions = batchRequest.requests().stream()
                .map(r -> evaluator.evaluate(subject, r))
                .toList();
        return Response.ok(new BatchEvaluationResult(decisions)).build();
    }
}
