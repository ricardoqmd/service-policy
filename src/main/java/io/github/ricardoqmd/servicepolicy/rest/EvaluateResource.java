package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;
import io.quarkus.security.Authenticated;

/**
 * PEP-facing authorization evaluation endpoints.
 *
 * <p>Accepts evaluation requests from Policy Enforcement Points (PEPs) and returns
 * {@link Decision} responses from the configured {@link PolicyEvaluator}.
 *
 * <p>Subject is resolved from the validated Bearer JWT (ADR-013). An explicit {@code subject}
 * in the request body enables delegated queries (hybrid rule, ADR-013 §5).
 *
 * <p>The application scope is the path coordinate (ADR-026), not a body field: it determines which
 * policies exist for the call at all, so it is expressed the same way on both planes. A body that
 * still carries {@code app} is rejected with 400 rather than reconciled with the route — bodies are
 * strict, and {@link UnknownPropertyMapper} turns the stray field into the problem response.
 */
@Path("/v1/apps/{app}/evaluate")
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
                    + " given resource, within the application named in the path (ADR-026). Subject is"
                    + " resolved from the validated Bearer JWT (ADR-013). An explicit 'subject' in the"
                    + " body enables delegated queries; 403 if the delegation marker is absent. Returns"
                    + " 401 if unauthenticated, 400 if 'action' or 'resource.type' is blank, or if the"
                    + " body carries an 'app' field (it is determined by the path).")
    public Response evaluate(@PathParam("app") String app, EvaluationRequest request) {
        if (request == null) {
            throw new InvalidRequestException("request body must not be empty.");
        }
        if (request.action() == null || request.action().isBlank()) {
            throw new InvalidRequestException("action must not be blank.");
        }
        if (request.resource() == null
                || request.resource().type() == null
                || request.resource().type().isBlank()) {
            throw new InvalidRequestException("resource.type must not be blank.");
        }
        String subject = authContext.resolveEffectiveSubject(request.subject());
        return Response.ok(evaluator.evaluate(app, subject, request)).build();
    }

    @POST
    @Path("/batch")
    @Operation(
            summary = "Evaluate a batch of authorization requests",
            description = "Evaluates multiple requests in a single call, all within the application named in the"
                    + " path (ADR-026). Results are returned in the same order as the input requests."
                    + " Per-item resource-validation errors are returned as deny decisions (not HTTP"
                    + " 400). An 'app' field on any item fails the entire batch with 400 — the scope is"
                    + " the path's, and no item may claim its own. A delegation violation on any item"
                    + " fails the entire batch with 403. Requires a valid Bearer token.")
    public Response batch(@PathParam("app") String app, BatchEvaluationRequest batchRequest) {
        List<Decision> decisions = batchRequest.requests().stream()
                .map(r -> evaluator.evaluate(app, authContext.resolveEffectiveSubject(r.subject()), r))
                .toList();
        return Response.ok(new BatchEvaluationResult(decisions)).build();
    }
}
