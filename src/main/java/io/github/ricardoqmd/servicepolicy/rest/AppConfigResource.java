package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;
import java.util.function.Consumer;

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
import io.github.ricardoqmd.servicepolicy.persistence.AppConfig;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.PipConfig;
import io.github.ricardoqmd.servicepolicy.problem.AppConfigNotFoundException;
import io.github.ricardoqmd.servicepolicy.problem.ForbiddenProblemException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidAppConfigException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionRequiredException;
import io.github.ricardoqmd.servicepolicy.problem.ProblemDetail;
import io.quarkus.security.Authenticated;

/**
 * PAP-facing per-application configuration endpoints (ADR-029): the claim-to-attribute mapping and
 * the attribute-source settings of one application.
 *
 * <p>Configuration is <em>data</em>, not deployment config. What is global and deployment-shaped stays in
 * {@code application.yml}; what is per-application by nature lives here, so onboarding or reconfiguring an application is an API call
 * rather than a redeploy that restarts every other application's engine.
 *
 * <p>A SINGLETON resource, not a collection: one document per app, addressed by the path alone.
 * There is no list endpoint because there is nothing to list within an application.
 *
 * <p>Authorized per application (ADR-033) and nested under its application (ADR-026), so {@code app} comes
 * from the path and must not appear in any body. Reads need {@code policy:read} and writes {@code
 * policy:write} on that application; a write may declare on whose behalf it is made with the body field
 * {@code subject}, which authorizes nothing and is recorded in the audit (ADR-033 §4).
 *
 * <p><strong>The reserved control-plane application (ADR-033 §6).</strong> Its configuration holds the claim
 * mapping every control-plane decision applies. It cannot be created or deleted here — {@code 403}, whatever
 * the caller's policies grant, once the caller is authorized for it at all — and it is replaced through the
 * ordinary {@code PUT}, which refuses a mapping that would leave the caller itself without the reserved
 * application. Conditional writes follow the same contract as
 * everything else administrative: a strong ETag equal to the revision, {@code If-Match} required on
 * {@code PUT}/{@code DELETE}, 428 when absent, 412 when stale (ADR-018).
 *
 * <p><strong>Where the transport boundary sits in that ordering.</strong> A request that never
 * yields a parseable document — an absent or empty body, malformed JSON, an undeclared field such as
 * {@code app} — is rejected with 400 <em>before</em> the conditional request is evaluated. That is
 * not a violation of precondition-first: there is no document to check a precondition on behalf of,
 * and a client that could not serialise its intent learns that first. The
 * precondition-before-validation ordering applies from the moment a syntactically valid document
 * exists, and it holds there — a stale {@code If-Match} carrying a semantically invalid document
 * returns 412, not the validation report.
 *
 * <p><strong>Who consumes it.</strong> The claim mapping is read by {@code GET /permissions} (ADR-030) and,
 * for the reserved application only, by the control-plane gate (ADR-033). The PIP adapter is a later ADR.
 * {@code /evaluate} is untouched by this resource.
 */
@Path("/v1/apps/{app}/configuration")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "configuration", description = "PAP-facing per-application configuration (ADR-029).")
@Authenticated
public class AppConfigResource {

    /** The detail of the refusal to create or delete the reserved application's configuration. */
    static final String RESERVED =
            "the configuration of the reserved control-plane application cannot be created or" + " deleted.";

    private final AppConfigStore configStore;
    private final ControlPlaneGate gate;

    AppConfigResource(AppConfigStore configStore, ControlPlaneGate gate) {
        this.configStore = configStore;
        this.gate = gate;
    }

    @POST
    @Operation(
            summary = "Create the configuration of an application",
            description = "Creates this app's configuration document (ADR-029) at revision 1. Requires"
                    + " policy:write on this app (ADR-033); otherwise 403 FORBIDDEN, which does not say whether"
                    + " the app exists. The reserved control-plane application cannot be created: 403. The"
                    + " optional body field 'subject' declares on whose behalf the write is made; it authorizes"
                    + " nothing and is recorded in the audit (ADR-033 §4). The body must NOT carry an 'app' field — it is determined by the path"
                    + " (ADR-026). At least one of 'subjectAttributes' or 'pip' must be present; when 'pip'"
                    + " is present all four of its fields are required, its 'url' must be an absolute"
                    + " http/https URL containing the '{sub}' placeholder, and 'credentialRef' is a"
                    + " REFERENCE to a credential — secrets are never stored in this document, only the name"
                    + " the deployment's secret mechanism resolves. The configured URL is never contacted at"
                    + " write time. Returns 400 INVALID_APP_CONFIG listing every offending field, 409"
                    + " APP_CONFIG_ALREADY_EXISTS if the app already has a configuration (use PUT).")
    public Response create(@PathParam("app") String app, AppConfigWrite body) {
        ControlPlaneDecision decision = gate.authorize(app, ControlPlaneAction.WRITE);
        refuseReserved(app);
        requireBody(body);

        AppConfig created = configStore.create(app, toDraft(body), gate.actor(body.subject()));
        gate.writeSucceeded(decision);
        return Response.status(Response.Status.CREATED)
                .entity(view(created))
                .tag(etag(created))
                .build();
    }

    @GET
    @Operation(
            summary = "Get the configuration of an application",
            description = "Returns this app's configuration with a strong ETag equal to its current revision —"
                    + " the value to send back as If-Match when replacing or deleting it. Sections that are"
                    + " not configured are omitted rather than returned empty. Returns 404"
                    + " APP_CONFIG_NOT_FOUND if the app has no configuration; that is an administrative"
                    + " answer only, since an app without configuration evaluates normally (ADR-029). Requires"
                    + " policy:read on this app (ADR-033); otherwise 403 FORBIDDEN, which does not say whether"
                    + " the app exists.")
    public Response get(@PathParam("app") String app) {
        gate.authorize(app, ControlPlaneAction.READ);
        AppConfig config = configStore.find(app).orElseThrow(() -> new AppConfigNotFoundException(app));
        return Response.ok(view(config)).tag(etag(config)).build();
    }

    @PUT
    @Operation(
            summary = "Replace the configuration of an application",
            description = "Replaces the FULL configuration document (ADR-029) — this is a replace, not a merge,"
                    + " so a section omitted from the body is removed. Requires policy:write on this app"
                    + " (ADR-033) — otherwise 403 FORBIDDEN, which does not say whether the app exists — and an"
                    + " If-Match header with the current ETag. On the reserved control-plane application, a"
                    + " mapping that, resolved against the caller's own token, would not give the caller the"
                    + " reserved application in 'apps' is refused with 400 INVALID_APP_CONFIG and nothing is"
                    + " stored. The optional body field 'subject' declares on whose behalf the write is made; it"
                    + " authorizes nothing and is recorded in the audit (ADR-033 §4). Same body rules and same 'credentialRef'"
                    + " contract as create: secrets are never stored, only the reference. Returns 428 if"
                    + " If-Match is absent or unparseable, 412 if stale (checked before the body is"
                    + " validated), 404 if the app has no configuration, 400 INVALID_APP_CONFIG listing"
                    + " every offending field. A body that does not parse at all — absent, malformed, or"
                    + " carrying an unknown field — is a 400 before the precondition is evaluated: there is"
                    + " no document yet to check one against.")
    public Response replace(
            @PathParam("app") String app, @HeaderParam("If-Match") String ifMatch, AppConfigWrite body) {
        ControlPlaneDecision decision = gate.authorize(app, ControlPlaneAction.WRITE);
        long revision = parseIfMatch(ifMatch);
        requireBody(body);

        AppConfig replaced =
                configStore.replace(app, toDraft(body), revision, gate.actor(body.subject()), selfLockoutGuard(app));
        gate.writeSucceeded(decision);
        return Response.ok(view(replaced)).tag(etag(replaced)).build();
    }

    @DELETE
    @Operation(
            summary = "Delete the configuration of an application",
            description = "Removes this app's configuration (ADR-029). Requires policy:write on this app (ADR-033)"
                    + " — otherwise 403 FORBIDDEN, which does not say whether the app exists — and an If-Match"
                    + " header with the current ETag. The reserved control-plane application's configuration"
                    + " cannot be deleted: 403. Unguarded by design: the app returns to evaluating on"
                    + " caller-asserted attributes alone, which withdraws derived capability and can never"
                    + " widen a decision. Returns 428 if If-Match is absent or unparseable, 412 if stale, 404"
                    + " if the app has no configuration, 204 on success. There is no body, so the transport"
                    + " boundary has nothing to reject and the conditional request is evaluated first.")
    public Response delete(@PathParam("app") String app, @HeaderParam("If-Match") String ifMatch) {
        ControlPlaneDecision decision = gate.authorize(app, ControlPlaneAction.WRITE);
        refuseReserved(app);
        long revision = parseIfMatch(ifMatch);

        configStore.delete(app, revision);
        gate.writeSucceeded(decision);
        return Response.noContent().build();
    }

    /**
     * Refuses to create or delete the reserved application's configuration (ADR-033 §6), regardless of what
     * the caller's policies grant. It runs after the gate on purpose: a caller not authorized for the reserved
     * application receives the ordinary denial, so this refusal cannot be used to learn its identifier.
     */
    private void refuseReserved(String app) {
        if (gate.isReservedApp(app)) {
            throw new ForbiddenProblemException(RESERVED);
        }
    }

    /**
     * The self-lockout guard of ADR-033 §6, for a replace of the reserved application's configuration: the
     * proposed mapping, resolved against the caller's own validated token, must still give the caller the
     * reserved application. It runs where validation runs — after the precondition, before the write — so a
     * refused write leaves the stored document exactly as it was. Any other application has no such guard.
     */
    private Consumer<AppConfigDraft> selfLockoutGuard(String app) {
        if (!gate.isReservedApp(app)) {
            return draft -> {};
        }
        return draft -> {
            if (!gate.keepsCallerInReservedApp(draft.subjectAttributes())) {
                throw new InvalidAppConfigException(List.of(new ProblemDetail.InvalidParam(
                        "subjectAttributes.apps",
                        "resolved against the caller's own token, this mapping would not give the caller the"
                                + " reserved control-plane application; the change is refused so that whoever makes"
                                + " it can always undo it")));
            }
        };
    }

    /**
     * A missing body is a malformed request, not an invalid configuration: there is no document to
     * report violations against, so this is BAD_REQUEST rather than INVALID_APP_CONFIG. A body that
     * is present but configures nothing ({@code {}}) is the latter, and the store says so.
     */
    private static void requireBody(AppConfigWrite body) {
        if (body == null) {
            throw new InvalidRequestException("request body must not be empty.");
        }
    }

    /** Crosses into the persistence layer's own write shape; validation happens there (ADR-029). */
    private static AppConfigDraft toDraft(AppConfigWrite body) {
        AppConfigDraft.PipDraft pip = body.pip() == null
                ? null
                : new AppConfigDraft.PipDraft(
                        body.pip().url(),
                        body.pip().timeoutMs(),
                        body.pip().cacheTtlSeconds(),
                        body.pip().credentialRef());
        return new AppConfigDraft(body.subjectAttributes(), pip);
    }

    private static AppConfigView view(AppConfig config) {
        return new AppConfigView(config.app(), config.subjectAttributes(), pipView(config.pip()), config.revision());
    }

    private static AppConfigView.PipConfigView pipView(PipConfig pip) {
        return pip == null
                ? null
                : new AppConfigView.PipConfigView(
                        pip.url(), pip.timeoutMs(), pip.cacheTtlSeconds(), pip.credentialRef());
    }

    private static EntityTag etag(AppConfig config) {
        return new EntityTag(String.valueOf(config.revision()));
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
