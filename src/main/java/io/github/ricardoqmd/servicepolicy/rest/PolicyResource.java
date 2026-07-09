package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentException;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHead;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersion;
import io.github.ricardoqmd.servicepolicy.problem.ForbiddenProblemException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;
import io.github.ricardoqmd.servicepolicy.problem.PolicyNotFoundException;
import io.github.ricardoqmd.servicepolicy.problem.PolicyValidationException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionRequiredException;
import io.github.ricardoqmd.servicepolicy.problem.ProblemDetail;
import io.github.ricardoqmd.servicepolicy.problem.VersionNotFoundException;
import io.quarkus.security.Authenticated;

/**
 * PAP-facing policy endpoints.
 *
 * <p>All operations require the admin authorization marker (ADR-013 §4). The Bearer JWT is
 * validated by quarkus-oidc; the admin marker is then checked programmatically.
 *
 * <p>Authoring ({@code POST}) and appending ({@code PUT}) write through the head-pointer model
 * (ADR-016) following the transaction-free write invariants of ADR-019. Reads ({@code GET}) return
 * the head-pointer model using the response contract of ADR-017.
 */
@Path("/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "policies", description = "PAP-facing policy authoring and read endpoints.")
@Authenticated
public class PolicyResource {

    private static final int MAX_PAGE_SIZE = 100;

    private final PolicyLifecycleStore lifecycleStore;
    private final AuthContext authContext;
    private final ServicePolicyConfig cfg;
    private final PolicyDocumentMapper policyMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());
    private final PolicyReadMapper readMapper = new PolicyReadMapper();

    PolicyResource(PolicyLifecycleStore lifecycleStore, AuthContext authContext, ServicePolicyConfig cfg) {
        this.lifecycleStore = lifecycleStore;
        this.authContext = authContext;
        this.cfg = cfg;
    }

    @POST
    @Operation(
            summary = "Create a policy",
            description = "Creates a new inactive policy (ADR-014, ADR-019). Requires the admin marker."
                    + " Returns 400 if the body is malformed, 409 if a policy with the same id already"
                    + " exists. The policy is not evaluable until activated.")
    public Response create(Map<String, Object> body) {
        requireAdmin();

        if (body == null || body.isEmpty()) {
            throw new InvalidRequestException("request body must not be empty.");
        }

        Policy policy;
        try {
            policy = policyMapper.fromDocument(body);
        } catch (PolicyDocumentException e) {
            throw new PolicyValidationException(List.of(new ProblemDetail.InvalidParam("policy", e.getMessage())));
        }

        String changeReason = body.get("changeReason") instanceof String s ? s : null;
        lifecycleStore.create(policy, authContext.callerSubject(), changeReason);
        return Response.status(Response.Status.CREATED)
                .entity(new PolicyCreated(policy.id(), 1, false))
                .build();
    }

    @PUT
    @Path("/{id}")
    @Operation(
            summary = "Append a new version",
            description = "Appends an inactive version N+1 (ADR-019). Requires the admin marker and an"
                    + " If-Match header with the current ETag. Returns 428 if If-Match is absent,"
                    + " 412 if stale, 404 if policy unknown, 400 if body invalid.")
    public Response append(
            @PathParam("id") String id, @HeaderParam("If-Match") String ifMatch, WriteVersionRequest body) {
        requireAdmin();
        long revision = parseIfMatch(ifMatch);

        if (body == null || body.content() == null || body.content().isEmpty()) {
            throw new InvalidRequestException("request body 'content' must not be empty.");
        }

        Policy policy;
        try {
            policy = policyMapper.fromDocument(body.content());
        } catch (PolicyDocumentException e) {
            throw new PolicyValidationException(List.of(new ProblemDetail.InvalidParam("content", e.getMessage())));
        }

        int newVersion = lifecycleStore.append(id, policy, revision, authContext.callerSubject(), body.changeReason());
        return Response.ok(new PolicyCreated(id, newVersion, false)).build();
    }

    @POST
    @Path("/{id}/activate")
    @Operation(
            summary = "Activate a specific policy version",
            description = "Activates the named version (ADR-020). Requires the admin marker and an"
                    + " If-Match header with the head's current ETag. Returns 428 if If-Match is"
                    + " absent or unparseable, 412 if stale (with currentRevision), 404 if the"
                    + " policy or the requested version does not exist.")
    public Response activate(
            @PathParam("id") String id, @HeaderParam("If-Match") String ifMatch, ActivateRequest body) {
        requireAdmin();
        long revision = parseIfMatch(ifMatch);
        if (body == null || body.version() == null) {
            throw new InvalidRequestException("request body must include a 'version' number.");
        }
        PolicyHead head =
                lifecycleStore.activate(id, body.version(), revision, authContext.callerSubject(), body.changeReason());
        return Response.ok(readMapper.headView(head))
                .tag(new EntityTag(String.valueOf(head.revision())))
                .build();
    }

    @POST
    @Path("/{id}/deactivate")
    @Operation(
            summary = "Deactivate a policy",
            description = "Clears the active version pointer — a soft retire that preserves version"
                    + " history (ADR-014, ADR-020). Requires the admin marker and If-Match."
                    + " Body is optional (only changeReason). Returns 428, 412, or 404 on errors.")
    public Response deactivate(
            @PathParam("id") String id, @HeaderParam("If-Match") String ifMatch, DeactivateRequest body) {
        requireAdmin();
        long revision = parseIfMatch(ifMatch);
        String changeReason = body != null ? body.changeReason() : null;
        PolicyHead head = lifecycleStore.deactivate(id, revision, authContext.callerSubject(), changeReason);
        return Response.ok(readMapper.headView(head))
                .tag(new EntityTag(String.valueOf(head.revision())))
                .build();
    }

    @GET
    @Operation(
            summary = "List active policies",
            description = "Returns active policy heads (ADR-016) as a paginated collection (ADR-017).")
    public Response list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("view") String view) {
        requireAdmin();
        validatePaging(page, size);

        long total = lifecycleStore.countActiveHeads();
        List<PolicyHead> heads = lifecycleStore.findActiveHeads(page - 1, size);
        Paginated.Pagination pagination = Paginated.Pagination.of(page, size, total);

        if (isFull(view)) {
            return Response.ok(new Paginated<>(
                            heads.stream().map(readMapper::headView).toList(), pagination))
                    .build();
        }
        return Response.ok(new Paginated<>(
                        heads.stream().map(readMapper::headSummary).toList(), pagination))
                .build();
    }

    @GET
    @Path("/{id}")
    @Operation(
            summary = "Get a policy head",
            description = "Returns the full policy head. Response includes a strong ETag equal to the"
                    + " head's current revision. 404 if no policy has the given id.")
    public Response getById(@PathParam("id") String id) {
        requireAdmin();
        PolicyHead head = lifecycleStore.findHead(id).orElseThrow(() -> new PolicyNotFoundException(id));
        return Response.ok(readMapper.headView(head))
                .tag(new EntityTag(String.valueOf(head.revision())))
                .build();
    }

    @GET
    @Path("/{id}/versions")
    @Operation(
            summary = "List versions of a policy",
            description = "Returns the policy's versions (newest first) as a paginated collection.")
    public Response listVersions(
            @PathParam("id") String id,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("view") String view) {
        requireAdmin();
        validatePaging(page, size);
        if (!lifecycleStore.headExists(id)) {
            throw new PolicyNotFoundException(id);
        }

        long total = lifecycleStore.countVersions(id);
        List<PolicyVersion> versions = lifecycleStore.findVersions(id, page - 1, size);
        Paginated.Pagination pagination = Paginated.Pagination.of(page, size, total);

        if (isFull(view)) {
            return Response.ok(new Paginated<>(
                            versions.stream().map(readMapper::versionContent).toList(), pagination))
                    .build();
        }
        return Response.ok(new Paginated<>(
                        versions.stream().map(readMapper::versionSummary).toList(), pagination))
                .build();
    }

    @GET
    @Path("/{id}/versions/{version}")
    @Operation(
            summary = "Get a specific policy version",
            description = "Returns the full content of one immutable version. 404 if the policy or"
                    + " version does not exist.")
    public Response getVersion(@PathParam("id") String id, @PathParam("version") int version) {
        requireAdmin();
        return lifecycleStore
                .findVersion(id, version)
                .map(found -> Response.ok(readMapper.versionContent(found)).build())
                .orElseThrow(() -> new VersionNotFoundException(id, version));
    }

    private void requireAdmin() {
        if (!authContext.has(cfg.authz().admin())) {
            throw new ForbiddenProblemException("admin marker required.");
        }
    }

    private static void validatePaging(int page, int size) {
        if (page < 1) {
            throw new InvalidRequestException("'page' must be 1 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("'size' must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
    }

    private static boolean isFull(String view) {
        return "full".equalsIgnoreCase(view);
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        try {
            String stripped = ifMatch.startsWith("\"") && ifMatch.endsWith("\"")
                    ? ifMatch.substring(1, ifMatch.length() - 1)
                    : ifMatch;
            return Long.parseLong(stripped.trim());
        } catch (NumberFormatException e) {
            throw new PreconditionRequiredException();
        }
    }
}
