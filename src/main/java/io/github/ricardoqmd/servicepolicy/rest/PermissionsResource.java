package io.github.ricardoqmd.servicepolicy.rest;

import java.time.Instant;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.evaluation.PermissionsResponse;
import io.github.ricardoqmd.servicepolicy.evaluation.PolicyEvaluator;

/**
 * PEP-facing permissions listing endpoint.
 *
 * <p>Returns a flat, cacheable list of actions the authenticated subject may perform within a given
 * application context. PEPs can cache this response per {@code subject + app + policyVersion}. The
 * {@code policyVersion} field changes whenever the active policy set changes and can be used as a
 * cache-invalidation signal.
 */
@Path("/v1/permissions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "permissions", description = "Subject permission listing endpoint.")
public class PermissionsResource {

    private final PolicyEvaluator evaluator;
    private final SubjectResolver subjectResolver;

    PermissionsResource(PolicyEvaluator evaluator, SubjectResolver subjectResolver) {
        this.evaluator = evaluator;
        this.subjectResolver = subjectResolver;
    }

    @GET
    @Operation(
            summary = "List permitted actions for the authenticated subject",
            description = "Returns a flat, cacheable list of actions the subject is permitted to perform within"
                    + " the given application context. Subject is resolved from the Bearer JWT in the"
                    + " Authorization header. The response is safe to cache per subject + app +"
                    + " policyVersion; the policyVersion field changes when policies are updated."
                    + " Returns HTTP 401 if the Authorization header is missing or malformed. Returns"
                    + " HTTP 400 if the 'app' query parameter is missing or blank.")
    public Response permissions(@HeaderParam("Authorization") String authorization, @QueryParam("app") String app) {
        String subject = subjectResolver.resolve(authorization);
        if (app == null || app.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiError("BAD_REQUEST", "'app' query parameter must not be blank."))
                    .build();
        }
        return Response.ok(new PermissionsResponse(
                        subject,
                        app,
                        evaluator.permittedActions(subject, app),
                        evaluator.policyVersion(),
                        Instant.now().toString()))
                .build();
    }
}
