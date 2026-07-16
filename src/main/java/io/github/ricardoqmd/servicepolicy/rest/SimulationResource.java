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
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.evaluation.EvaluationRequest;
import io.github.ricardoqmd.servicepolicy.evaluation.PolicyEvaluator;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentException;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.problem.ForbiddenProblemException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;
import io.github.ricardoqmd.servicepolicy.problem.PolicyValidationException;
import io.github.ricardoqmd.servicepolicy.problem.ProblemDetail;
import io.quarkus.security.Authenticated;

/**
 * PAP-facing policy-simulation endpoint (ADR-027): a dry-run of the decision engine against a
 * caller-supplied policy document, with zero effect on stored state.
 *
 * <p>This is a sub-resource action ({@code :simulate}) on the app's policy collection — "simulate a
 * decision in this app". It lives on its own resource rather than on {@link PolicyResource} for two
 * reasons: (1) the {@code policies:simulate} suffix is a single path segment, which JAX-RS cannot
 * express as a sub-path of {@code /v1/apps/&#123;app&#125;/policies} (it would insert a separator and
 * yield {@code /policies/:simulate}); a dedicated resource owns the exact path. (2) Simulation is a
 * non-CRUD evaluation operation that depends on the {@link PolicyEvaluator} decision core, not on
 * the authoring/read machinery {@link PolicyResource} carries — keeping them apart avoids mixing the
 * data-plane engine into the authoring resource.
 *
 * <p>Admin-gated (ADR-013): it is an administration tool, so it checks the admin marker rather than
 * the ordinary evaluation gate. A non-admin caller gets 403.
 *
 * <p>The candidate {@code policy} is validated exactly as on create — through the same
 * {@link PolicyDocumentMapper#fromDocument} path — <em>before</em> any evaluation runs, so the
 * simulator never evaluates a document that could not have been created (ADR-023, ADR-027). App
 * lives in the path only (ADR-026): a {@code policy} carrying {@code app} is rejected with
 * {@code INVALID_POLICY} (by {@code fromDocument}); a {@code request} carrying {@code app} is
 * rejected with {@code BAD_REQUEST} (at deserialization, via {@link UnknownPropertyMapper}).
 */
@Path("/v1/apps/{app}/policies:simulate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "policies", description = "PAP-facing policy authoring and read endpoints.")
@Authenticated
public class SimulationResource {

    private final PolicyEvaluator evaluator;
    private final AuthContext authContext;
    private final ServicePolicyConfig cfg;
    private final PolicyDocumentMapper policyMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    SimulationResource(PolicyEvaluator evaluator, AuthContext authContext, ServicePolicyConfig cfg) {
        this.evaluator = evaluator;
        this.authContext = authContext;
        this.cfg = cfg;
    }

    @POST
    @Operation(
            summary = "Simulate a decision against an unsaved policy document",
            description = "Runs the decision engine against a policy document supplied in the request, without"
                    + " persisting anything and without touching active state (ADR-027). Requires the admin"
                    + " marker (ADR-013); 403 otherwise. The candidate 'policy' is validated exactly as on"
                    + " create — a malformed document or bad operand type (ADR-023) is rejected with 400"
                    + " INVALID_POLICY before any evaluation runs. Neither 'policy' nor 'request' may carry"
                    + " 'app' (ADR-026): it is the path's — a 'policy' that does returns 400 INVALID_POLICY,"
                    + " a 'request' that does returns 400 BAD_REQUEST. Returns the same 200 Decision as"
                    + " /evaluate.")
    public Response simulate(@PathParam("app") String app, SimulationRequest body) {
        requireAdmin();

        if (body == null || body.policy() == null || body.policy().isEmpty()) {
            throw new InvalidRequestException("request body 'policy' must not be empty.");
        }
        EvaluationRequest request = body.request();
        if (request == null) {
            throw new InvalidRequestException("request body 'request' must not be empty.");
        }
        if (request.action() == null || request.action().isBlank()) {
            throw new InvalidRequestException("action must not be blank.");
        }
        if (request.resource() == null
                || request.resource().type() == null
                || request.resource().type().isBlank()) {
            throw new InvalidRequestException("resource.type must not be blank.");
        }

        // Validate the candidate exactly as create/append do (ADR-027): the simulator never evaluates
        // a document that could not have been created. A body-carried 'app' (ADR-026) or a bad operand
        // type (ADR-023) is rejected here as INVALID_POLICY, before the engine ever runs.
        Policy candidate;
        try {
            candidate = policyMapper.fromDocument(body.policy());
        } catch (PolicyDocumentException e) {
            throw new PolicyValidationException(List.of(new ProblemDetail.InvalidParam("policy", e.getMessage())));
        }

        // App comes from the path (ADR-026); subject from the JWT. Zero effect — pure (candidate, request).
        String subject = authContext.resolveEffectiveSubject(request.subject());
        return Response.ok(evaluator.simulate(app, subject, request, candidate)).build();
    }

    private void requireAdmin() {
        if (!authContext.has(cfg.authz().admin())) {
            throw new ForbiddenProblemException("admin marker required.");
        }
    }
}
