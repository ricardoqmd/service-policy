package io.github.ricardoqmd.servicepolicy.rest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneAction;
import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneDecision;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueEntry;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueStore;
import io.github.ricardoqmd.servicepolicy.problem.CatalogueEntryNotFoundException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionRequiredException;
import io.quarkus.security.Authenticated;

/**
 * PAP-facing action catalogue endpoints (ADR-028): the set of action tokens that exist for a
 * resource type in an application.
 *
 * <p>The catalogue is administered, not deployed — a new application, or a new verb, must not
 * require redeploying the engine — so it is a first-class resource on the admin surface, keyed by
 * {@code (app, resourceType)} because verbs belong to a resource type rather than floating free at
 * app level. Every operation is authorized per application (ADR-033) — reads need {@code policy:read}
 * and writes {@code policy:write} on the route's application — and every route is nested under its
 * application (ADR-026): {@code app} comes from the path and must not appear in any body. A write may
 * declare on whose behalf it is made with the body field {@code subject}, which authorizes nothing and is
 * recorded in the audit (ADR-033 §4).
 *
 * <p>The catalogue is read at <em>authoring</em> only. {@code POST}/{@code PUT} of a policy expand
 * {@code ["*"]} against it and reject uncatalogued actions; {@code /evaluate} never touches it, so
 * this resource adds no read to the hot path.
 *
 * <p>Reads and conditional writes follow the same contract as policies: a strong ETag equal to the
 * entry's revision, {@code If-Match} required on {@code PUT}/{@code DELETE}, 428 when absent, 412
 * when stale (ADR-018).
 */
@Path("/v1/apps/{app}/action-catalogue")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "action-catalogue", description = "PAP-facing action catalogue administration (ADR-028).")
@Authenticated
public class ActionCatalogueResource {

    private static final String WILDCARD = "*";

    private final ActionCatalogueStore catalogueStore;
    private final ControlPlaneGate gate;

    ActionCatalogueResource(ActionCatalogueStore catalogueStore, ControlPlaneGate gate) {
        this.catalogueStore = catalogueStore;
        this.gate = gate;
    }

    @POST
    @Operation(
            summary = "Declare the action catalogue of a resource type",
            description = "Creates the catalogue entry for one resource type in this app (ADR-028), at revision 1."
                    + " Requires policy:write on this app (ADR-033); otherwise 403 FORBIDDEN, which does not say"
                    + " whether the app exists. The optional body field 'subject' declares on whose behalf the"
                    + " write is made; it authorizes nothing and is recorded in the audit (ADR-033 §4). The body must NOT carry an 'app' field — it is determined"
                    + " by the path (ADR-026). 'actions' must be non-empty, without blanks or duplicates, and"
                    + " may not contain '*': the catalogue IS the explicit set that '*' expands to. Returns"
                    + " 400 on a malformed body, 409 CATALOGUE_ENTRY_ALREADY_EXISTS if the app already"
                    + " declares that resource type.")
    public Response create(@PathParam("app") String app, CatalogueEntryCreate body) {
        ControlPlaneDecision decision = gate.authorize(app, ControlPlaneAction.WRITE);

        if (body == null) {
            throw new InvalidRequestException("request body must not be empty.");
        }
        if (body.resourceType() == null || body.resourceType().isBlank()) {
            throw new InvalidRequestException("'resourceType' must not be blank.");
        }
        validateActions(body.actions());

        ActionCatalogueEntry entry =
                catalogueStore.create(app, body.resourceType(), body.actions(), gate.actor(body.subject()));
        gate.writeSucceeded(decision);
        return Response.status(Response.Status.CREATED)
                .entity(view(entry))
                .tag(etag(entry))
                .build();
    }

    /**
     * Lists every entry of the app, under the {@code data} envelope key every collection in this API
     * uses (see {@link Paginated}) — minus the {@code pagination} block, which has nothing to say
     * about a collection that is always returned whole.
     *
     * <p>Deliberately NOT paginated, unlike every other listing in this API. A catalogue is a bounded
     * vocabulary — an application has tens of resource types, not thousands — so paging would add a
     * contract and a client loop to protect against a size that cannot occur. It would also work
     * against the consumers: expanding {@code '*'} and enumerating a subject's permissions (ADR-030)
     * both need the vocabulary <em>whole</em>, and a partial catalogue is not a smaller answer, it is
     * a wrong one.
     */
    @GET
    @Operation(
            summary = "List the action catalogue of an application",
            description = "Returns every catalogue entry of this app (ADR-028), ordered by resource type, as"
                    + " {\"data\": [...]} — the same envelope key the paginated listings use, without a"
                    + " 'pagination' block. Not paginated by design: a catalogue is a bounded vocabulary and"
                    + " its consumers — wildcard expansion and permission enumeration — need it whole. Returns"
                    + " an empty list if the app declares no resource type. Requires policy:read on this app"
                    + " (ADR-033); otherwise 403 FORBIDDEN, which does not say whether the app exists.")
    public Response list(@PathParam("app") String app) {
        gate.authorize(app, ControlPlaneAction.READ);
        List<CatalogueEntryView> entries = catalogueStore.list(app).stream()
                .map(ActionCatalogueResource::view)
                .toList();
        return Response.ok(new CatalogueEntries(entries)).build();
    }

    @GET
    @Path("/{resourceType}")
    @Operation(
            summary = "Get the action catalogue of a resource type",
            description = "Returns the entry for one resource type in this app, with a strong ETag equal to its"
                    + " current revision — the value to send back as If-Match when replacing or deleting it."
                    + " Returns 404 CATALOGUE_ENTRY_NOT_FOUND if the app does not declare that resource type."
                    + " Requires policy:read on this app (ADR-033); otherwise 403 FORBIDDEN, which does not say"
                    + " whether the app exists.")
    public Response get(@PathParam("app") String app, @PathParam("resourceType") String resourceType) {
        gate.authorize(app, ControlPlaneAction.READ);
        ActionCatalogueEntry entry = catalogueStore
                .find(app, resourceType)
                .orElseThrow(() -> new CatalogueEntryNotFoundException(app, resourceType));
        return Response.ok(view(entry)).tag(etag(entry)).build();
    }

    @PUT
    @Path("/{resourceType}")
    @Operation(
            summary = "Replace the action set of a resource type",
            description = "Replaces the FULL action set of the entry (ADR-028) — this is a replace, not a merge."
                    + " Requires policy:write on this app (ADR-033) — otherwise 403 FORBIDDEN, which does not say"
                    + " whether the app exists — and an If-Match header with the current ETag. The optional"
                    + " body field 'subject' declares on whose behalf the write is made; it authorizes nothing"
                    + " and is recorded in the audit (ADR-033 §4). Adding actions"
                    + " is always safe: '*' was expanded at authoring, so no existing policy changes meaning."
                    + " Removing one is guarded — an action still named by an ACTIVE policy of this resource"
                    + " type returns 409 ACTION_IN_USE, listing the blocking policy ids. Returns 428 if"
                    + " If-Match is absent or unparseable, 412 if stale, 404 if the entry is unknown, 400 if"
                    + " the body is invalid.")
    public Response replace(
            @PathParam("app") String app,
            @PathParam("resourceType") String resourceType,
            @HeaderParam("If-Match") String ifMatch,
            CatalogueEntryReplace body) {
        ControlPlaneDecision decision = gate.authorize(app, ControlPlaneAction.WRITE);
        long revision = parseIfMatch(ifMatch);

        if (body == null) {
            throw new InvalidRequestException("request body must not be empty.");
        }
        validateActions(body.actions());

        ActionCatalogueEntry entry =
                catalogueStore.replace(app, resourceType, body.actions(), revision, gate.actor(body.subject()));
        gate.writeSucceeded(decision);
        return Response.ok(view(entry)).tag(etag(entry)).build();
    }

    @DELETE
    @Path("/{resourceType}")
    @Operation(
            summary = "Delete the action catalogue of a resource type",
            description = "Undeclares the resource type's vocabulary (ADR-028). Requires policy:write on this app"
                    + " (ADR-033) — otherwise 403 FORBIDDEN, which does not say whether the app exists — and an"
                    + " If-Match header with the current ETag. Deleting the entry removes every action at once,"
                    + " so it is blocked with 409 ACTION_IN_USE while ANY of them is named by an active policy."
                    + " Returns 428 if If-Match is absent or unparseable, 412 if stale, 404 if the entry is"
                    + " unknown, 204 on success. Policies of that resource type can no longer be authored"
                    + " afterwards until the catalogue is declared again.")
    public Response delete(
            @PathParam("app") String app,
            @PathParam("resourceType") String resourceType,
            @HeaderParam("If-Match") String ifMatch) {
        ControlPlaneDecision decision = gate.authorize(app, ControlPlaneAction.WRITE);
        long revision = parseIfMatch(ifMatch);

        catalogueStore.delete(app, resourceType, revision);
        gate.writeSucceeded(decision);
        return Response.noContent().build();
    }

    /**
     * Validates the action set of a create or a replace. These are 400 {@code BAD_REQUEST}, not
     * {@code INVALID_POLICY}: a catalogue entry is not a policy document, and reusing the policy code
     * would send a client looking for a policy it never sent.
     */
    private static void validateActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            throw new InvalidRequestException("'actions' must not be empty.");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String action : actions) {
            if (action == null || action.isBlank()) {
                throw new InvalidRequestException("'actions' must not contain blank elements.");
            }
            if (WILDCARD.equals(action)) {
                throw new InvalidRequestException(
                        "'*' is not a catalogue action; the catalogue is the explicit set it expands to.");
            }
            if (!seen.add(action)) {
                throw new InvalidRequestException("'actions' must not contain duplicates: '" + action + "'.");
            }
        }
    }

    private static CatalogueEntryView view(ActionCatalogueEntry entry) {
        return new CatalogueEntryView(entry.app(), entry.resourceType(), entry.actions(), entry.revision());
    }

    private static EntityTag etag(ActionCatalogueEntry entry) {
        return new EntityTag(String.valueOf(entry.revision()));
    }

    /** Same If-Match idiom as {@link PolicyResource}: absent or unparseable is 428, never a blind write. */
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
