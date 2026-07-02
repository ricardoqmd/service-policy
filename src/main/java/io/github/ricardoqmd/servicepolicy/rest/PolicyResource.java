package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentException;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyStore;

/**
 * PAP-facing policy authoring endpoints.
 *
 * <p>Accepts a policy in the same document shape used for persistence (ADR-012): a policy with an
 * ordered list of rules, each carrying a condition AST of comparisons and and/or nodes. The request
 * is mapped to the domain via the shared {@link PolicyDocumentMapper}, so authoring and storage
 * share one validated format.
 */
@Path("/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "policies", description = "PAP-facing policy authoring endpoints.")
public class PolicyResource {

    private final PolicyStore policyStore;
    private final SubjectResolver subjectResolver;
    private final PolicyDocumentMapper policyMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    PolicyResource(PolicyStore policyStore, SubjectResolver subjectResolver) {
        this.policyStore = policyStore;
        this.subjectResolver = subjectResolver;
    }

    @POST
    @Operation(
            summary = "Create a policy",
            description = "Creates a new active policy from the document-shaped body. Requires a Bearer token"
                    + " (HTTP 401 otherwise). Returns HTTP 400 if the body is malformed, or HTTP 409 if a"
                    + " policy with the same id already exists.")
    public Response create(@HeaderParam("Authorization") String authorization, Map<String, Object> body) {
        subjectResolver.resolve(authorization); // authenticate (throws HTTP 401 if missing/malformed)

        if (body == null || body.isEmpty()) {
            return badRequest("request body must not be empty.");
        }

        Policy policy;
        try {
            policy = policyMapper.fromDocument(body);
        } catch (PolicyDocumentException e) {
            return badRequest(e.getMessage());
        }

        if (policyStore.exists(policy.id())) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ApiError("CONFLICT", "a policy with id '" + policy.id() + "' already exists."))
                    .build();
        }

        policyStore.save(policy, true);
        return Response.status(Response.Status.CREATED)
                .entity(new PolicyCreated(policy.id(), policy.version(), true))
                .build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiError("BAD_REQUEST", message))
                .build();
    }
}
