package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
import io.github.ricardoqmd.servicepolicy.persistence.PolicyStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersion;
import io.quarkus.security.Authenticated;

/**
 * PAP-facing policy endpoints.
 *
 * <p>All operations require the admin authorization marker (ADR-013 §4). The Bearer JWT is validated
 * by quarkus-oidc; the admin marker is then checked programmatically.
 *
 * <p>Authoring ({@code POST}) accepts the document-shaped body (ADR-012). Reads ({@code GET}) return
 * the head-pointer model (ADR-016) using the response contract of ADR-017: collections are wrapped
 * in a {@link Paginated} envelope (1-indexed page/size), single resources are returned bare, and
 * lists are lean by default with an opt-in {@code ?view=full}.
 */
@Path("/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "policies", description = "PAP-facing policy authoring and read endpoints.")
@Authenticated
public class PolicyResource {

    private static final int MAX_PAGE_SIZE = 100;

    private final PolicyStore policyStore;
    private final PolicyLifecycleStore lifecycleStore;
    private final AuthContext authContext;
    private final ServicePolicyConfig cfg;
    private final PolicyDocumentMapper policyMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());
    private final PolicyReadMapper readMapper = new PolicyReadMapper();

    PolicyResource(
            PolicyStore policyStore,
            PolicyLifecycleStore lifecycleStore,
            AuthContext authContext,
            ServicePolicyConfig cfg) {
        this.policyStore = policyStore;
        this.lifecycleStore = lifecycleStore;
        this.authContext = authContext;
        this.cfg = cfg;
    }

    @POST
    @Operation(
            summary = "Create a policy",
            description = "Creates a new active policy from the document-shaped body. Requires a valid Bearer token"
                    + " (401 otherwise) and the admin authorization marker (403 otherwise). Returns"
                    + " 400 if the body is malformed, or 409 if a policy with the same id already exists.")
    public Response create(Map<String, Object> body) {
        if (!isAdmin()) {
            return forbidden();
        }

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

    @GET
    @Operation(
            summary = "List active policies",
            description = "Returns active policy heads (ADR-016) as a paginated collection (ADR-017). Lean by"
                    + " default; pass ?view=full to embed each head's active content. Requires a valid Bearer"
                    + " token (401) and the admin marker (403). Returns 400 for invalid pagination.")
    public Response list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("view") String view) {
        if (!isAdmin()) {
            return forbidden();
        }
        Response pagingError = validatePaging(page, size);
        if (pagingError != null) {
            return pagingError;
        }

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
            description = "Returns the full policy head, including its active content (null if the policy has no"
                    + " active version). 401/403 as above; 404 if no policy has the given id.")
    public Response getById(@PathParam("id") String id) {
        if (!isAdmin()) {
            return forbidden();
        }
        return lifecycleStore
                .findHead(id)
                .map(head -> Response.ok(readMapper.headView(head)).build())
                .orElseGet(() -> notFound("no policy with id '" + id + "'."));
    }

    @GET
    @Path("/{id}/versions")
    @Operation(
            summary = "List versions of a policy",
            description = "Returns the policy's versions (newest first) as a paginated collection (ADR-017); lean"
                    + " by default, ?view=full for full content. 401/403 as above; 404 if the policy id is"
                    + " unknown; 400 for invalid pagination.")
    public Response listVersions(
            @PathParam("id") String id,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("view") String view) {
        if (!isAdmin()) {
            return forbidden();
        }
        Response pagingError = validatePaging(page, size);
        if (pagingError != null) {
            return pagingError;
        }
        if (!lifecycleStore.headExists(id)) {
            return notFound("no policy with id '" + id + "'.");
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
            description = "Returns the full content of one immutable version. 401/403 as above; 404 if the policy"
                    + " or version does not exist.")
    public Response getVersion(@PathParam("id") String id, @PathParam("version") int version) {
        if (!isAdmin()) {
            return forbidden();
        }
        return lifecycleStore
                .findVersion(id, version)
                .map(found -> Response.ok(readMapper.versionContent(found)).build())
                .orElseGet(() -> notFound("policy '" + id + "' has no version " + version + "."));
    }

    private boolean isAdmin() {
        return authContext.has(cfg.authz().admin());
    }

    private static Response validatePaging(int page, int size) {
        if (page < 1) {
            return badRequest("'page' must be 1 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            return badRequest("'size' must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        return null;
    }

    private static boolean isFull(String view) {
        return "full".equalsIgnoreCase(view);
    }

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new ApiError("FORBIDDEN", "admin marker required."))
                .build();
    }

    private static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new ApiError("NOT_FOUND", message))
                .build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiError("BAD_REQUEST", message))
                .build();
    }
}
