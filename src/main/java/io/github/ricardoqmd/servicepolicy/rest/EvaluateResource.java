package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

import jakarta.ws.rs.Consumes;
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
import io.quarkus.security.Authenticated;

/**
 * PEP-facing authorization evaluation endpoints.
 *
 * <p>Accepts evaluation requests from Policy Enforcement Points (PEPs) and returns
 * {@link Decision} responses from the configured {@link PolicyEvaluator}.
 *
 * <p>Subject is resolved from the validated Bearer JWT (ADR-013). An explicit {@code subject}
 * in the request body enables delegated queries (hybrid rule, ADR-013 §5).
 */
@Path("/v1/evaluate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "evaluation", description = "PEP-facing authorization evaluation endpoints.")
@Authenticated
public class EvaluateResource {

    private final PolicyEvaluator evaluator;
    private final AuthContext authContext;

    EvaluateResource(PolicyEvaluator evaluator, AuthContext authContext) {
        this.evaluator = evaluator;
        this.authContext = authContext;
    }

    @POST
    @Operation(
            summary = "Evaluate a single authorization request",
            description = "Evaluates whether the authenticated subject may perform the requested action on the"
                    + " given resource. Subject is resolved from the validated Bearer JWT (ADR-013)."
                    + " An explicit 'subject' in the body enables delegated queries; 403 if the"
                    + " delegation marker is absent. Returns 401 if unauthenticated, 400 if"
                    + " 'action' or 'resource.type' is blank.")
    public Response evaluate(EvaluationRequest request) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw new InvalidRequestException("action must not be blank.");
        }
        if (request.resource() == null
                || request.resource().type() == null
                || request.resource().type().isBlank()) {
            throw new InvalidRequestException("resource.type must not be blank.");
        }
        String subject = authContext.resolveEffectiveSubject(request.subject());
        return Response.ok(evaluator.evaluate(subject, request)).build();
    }

    @POST
    @Path("/batch")
    @Operation(
            summary = "Evaluate a batch of authorization requests",
            description = "Evaluates multiple requests in a single call. Results are returned in the same order"
                    + " as the input requests. Per-item resource-validation errors are returned"
                    + " as deny decisions (not HTTP 400). A delegation violation on any item"
                    + " fails the entire batch with 403. Requires a valid Bearer token.")
    public Response batch(BatchEvaluationRequest batchRequest) {
        List<Decision> decisions = batchRequest.requests().stream()
                .map(r -> evaluator.evaluate(authContext.resolveEffectiveSubject(r.subject()), r))
                .toList();
        return Response.ok(new BatchEvaluationResult(decisions)).build();
    }
}
