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

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.github.ricardoqmd.servicepolicy.evaluation.BatchEvaluationRequest;
import io.github.ricardoqmd.servicepolicy.evaluation.BatchEvaluationResult;
import io.github.ricardoqmd.servicepolicy.evaluation.Decision;
import io.github.ricardoqmd.servicepolicy.evaluation.EvaluationRequest;
import io.github.ricardoqmd.servicepolicy.evaluation.PolicyEvaluator;
import io.github.ricardoqmd.servicepolicy.problem.BatchTooLargeException;
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
    private final ServicePolicyConfig cfg;

    EvaluateResource(PolicyEvaluator evaluator, AuthContext authContext, ServicePolicyConfig cfg) {
        this.evaluator = evaluator;
        this.authContext = authContext;
        this.cfg = cfg;
    }

    @POST
    @Operation(
            summary = "Evaluate a single authorization request",
            description = "Evaluates whether the authenticated subject may perform the requested action on the"
                    + " given resource, within the application named in the path (ADR-026). Subject is"
                    + " resolved from the validated Bearer JWT (ADR-013). An explicit 'subject' in the"
                    + " body enables delegated queries; 403 if the delegation marker is absent. Returns"
                    + " 401 if unauthenticated, 400 if 'action' or 'resource.type' is blank, or if the"
                    + " body carries an 'app' field (it is determined by the path). The action is 'verb' or"
                    + " 'type:verb', split at the first colon: when it carries a prefix, the prefix must be"
                    + " 'resource.type' exactly, or the request is refused before evaluation with 400"
                    + " ACTION_RESOURCE_TYPE_MISMATCH (ADR-036). A blank verb after the colon is 400"
                    + " BAD_REQUEST whatever the prefix: that check runs first.")
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
        ActionAgreement.check(request);
        String subject = authContext.resolveEffectiveSubject(request.subject());
        return Response.ok(evaluator.evaluate(app, subject, request)).build();
    }

    @POST
    @Path("/batch")
    @Operation(
            summary = "Evaluate a batch of authorization requests",
            description = "Evaluates multiple requests in a single call, all within the application named in the"
                    + " path (ADR-026). Results are returned in the same order as the input requests."
                    + " The batch carries between 1 and 'service-policy.evaluation.batch-max-size'"
                    + " items (default 100, the same bound the API applies to collection page size);"
                    + " an empty batch or one over the cap is rejected whole with 400, with no partial"
                    + " processing (ADR-031). Per-item resource-validation errors are returned as deny"
                    + " decisions (not HTTP 400). Before any item is evaluated, every item that carries a"
                    + " non-blank 'resource.type' is checked in order (ADR-036), and the first offending one"
                    + " fails the entire batch: an action whose verb after the colon is blank with 400"
                    + " BAD_REQUEST, whatever the prefix; an action whose prefix is not exactly that type"
                    + " with 400 ACTION_RESOURCE_TYPE_MISMATCH, naming its index. An item whose"
                    + " 'resource.type' is absent or blank is not checked by that rule, and is answered with"
                    + " a deny. An 'app' field on any item fails the entire batch"
                    + " with 400 — the scope is the path's, and no item may claim its own. A delegation"
                    + " violation on any item fails the entire batch with 403. Requires a valid Bearer"
                    + " token.")
    public Response batch(@PathParam("app") String app, BatchEvaluationRequest batchRequest) {
        // Size is checked BEFORE anything is evaluated: the point of the cap is to bound the work
        // the engine takes on, so it cannot be paid for by doing the work first (ADR-031).
        if (batchRequest == null
                || batchRequest.requests() == null
                || batchRequest.requests().isEmpty()) {
            throw new InvalidRequestException("'requests' must not be empty.");
        }
        int batchMaxSize = cfg.evaluation().batchMaxSize();
        if (batchRequest.requests().size() > batchMaxSize) {
            // Rejected whole, never truncated: a PEP that believes it evaluated N decisions and
            // received fewer is the worst possible contract for an authorization engine (ADR-031).
            throw new BatchTooLargeException(batchMaxSize);
        }
        // Every item is checked before the first is evaluated: a disagreeing action refuses the whole
        // batch, so no decision is computed that the caller will never receive (ADR-036 §5).
        ActionAgreement.checkEach(batchRequest.requests());
        List<Decision> decisions = batchRequest.requests().stream()
                .map(r -> evaluator.evaluate(app, authContext.resolveEffectiveSubject(r.subject()), r))
                .toList();
        return Response.ok(new BatchEvaluationResult(decisions)).build();
    }
}
