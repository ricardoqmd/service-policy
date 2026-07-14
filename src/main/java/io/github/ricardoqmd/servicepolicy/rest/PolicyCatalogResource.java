package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.github.ricardoqmd.servicepolicy.domain.policy.HeadStatus;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHead;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.problem.ForbiddenProblemException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;
import io.quarkus.security.Authenticated;

/**
 * Cross-application policy catalogue (ADR-026 §3): the one listing that spans applications.
 *
 * <p>Read-only by design. Every write and every per-app read lives on the app-nested resource
 * ({@link PolicyResource}); this exists because an administrator who supervises several applications
 * needs a single, server-paginated, merged view — which N nested calls cannot give (paginating a
 * client-side merge of independently paginated collections is broken by construction).
 *
 * <p>This is the only place where {@code ?app=} survives as a filter (ADR-024 §5): here the app is a
 * genuine optional filter over a cross-app collection, not the identity coordinate it is on the
 * nested routes. It composes with {@code ?status=} (ADR-025) as an AND.
 */
@Path("/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "policies", description = "Cross-application, read-only policy catalogue.")
@Authenticated
public class PolicyCatalogResource {

    private static final int MAX_PAGE_SIZE = 100;

    private final PolicyLifecycleStore lifecycleStore;
    private final AuthContext authContext;
    private final ServicePolicyConfig cfg;
    private final PolicyReadMapper readMapper = new PolicyReadMapper();

    PolicyCatalogResource(PolicyLifecycleStore lifecycleStore, AuthContext authContext, ServicePolicyConfig cfg) {
        this.lifecycleStore = lifecycleStore;
        this.authContext = authContext;
        this.cfg = cfg;
    }

    @GET
    @Operation(
            summary = "List policy heads across all applications",
            description = "Administrative cross-app catalogue (ADR-026): returns policy heads of every"
                    + " application as a paginated collection (ADR-017). Read-only — writes and per-app"
                    + " reads live under /v1/apps/{app}/policies. Optional filters, combined with AND:"
                    + " '?app=' scopes to one application, '?status=' filters by lifecycle state"
                    + " ('active', 'inactive' or 'all' — the default). An unknown 'status' returns 400.")
    public Response list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("view") String view,
            @QueryParam("app") String app,
            @QueryParam("status") String status,
            @Context UriInfo uriInfo) {
        requireAdmin();
        validatePaging(page, size);
        HeadStatus headStatus = parseStatus(status, uriInfo);

        long total = lifecycleStore.countHeads(app, headStatus);
        List<PolicyHead> heads = lifecycleStore.findHeads(app, headStatus, page - 1, size);
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

    /**
     * Only an absent '?status=' defaults to ALL; a present-but-empty one is an explicit invalid
     * value (ADR-025) — see the identical treatment in {@link PolicyResource}.
     */
    private static HeadStatus parseStatus(String status, UriInfo uriInfo) {
        if (!uriInfo.getQueryParameters().containsKey("status")) {
            return HeadStatus.ALL;
        }
        return HeadStatus.parse(status == null ? "" : status)
                .orElseThrow(() -> new InvalidRequestException("'status' must be one of: active, inactive, all."));
    }

    private static boolean isFull(String view) {
        return "full".equalsIgnoreCase(view);
    }
}
