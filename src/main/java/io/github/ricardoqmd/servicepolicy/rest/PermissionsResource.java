package io.github.ricardoqmd.servicepolicy.rest;

import java.time.Instant;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.evaluation.PermissionsResponse;
import io.github.ricardoqmd.servicepolicy.evaluation.PolicyEvaluator;
import io.quarkus.security.Authenticated;

/**
 * PEP-facing permissions listing endpoint.
 *
 * <p>Returns a flat, cacheable list of actions the authenticated subject may perform within a given
 * application context. PEPs can cache this response per {@code subject + app + policyVersion}. The
 * {@code policyVersion} field changes whenever the active policy set changes and can be used as a
 * cache-invalidation signal.
 *
 * <p>Subject is always the caller's own JWT subject (self-only). Delegated listing is deferred
 * to the bulk-permissions decision (ADR-013 §6).
 */
@Path("/v1/apps/{app}/permissions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "permissions", description = "Subject permission listing endpoint.")
@Authenticated
public class PermissionsResource {

    private final PolicyEvaluator evaluator;
    private final AuthContext authContext;

    PermissionsResource(PolicyEvaluator evaluator, AuthContext authContext) {
        this.evaluator = evaluator;
        this.authContext = authContext;
    }

    @GET
    @Operation(
            summary = "List permitted actions for the authenticated subject",
            description = "Returns a flat, cacheable list of actions the subject is permitted to perform within"
                    + " the application named in the path (ADR-026). Subject is resolved from the"
                    + " validated Bearer JWT (ADR-013). The response is safe to cache per subject + app +"
                    + " policyVersion; the policyVersion field changes when policies are updated."
                    + " Returns 401 if unauthenticated.")
    public Response permissions(@PathParam("app") String app) {
        String subject = authContext.callerSubject();
        return Response.ok(new PermissionsResponse(
                        subject,
                        app,
                        evaluator.permittedActions(subject, app),
                        evaluator.policyVersion(),
                        Instant.now().toString()))
                .build();
    }
}
