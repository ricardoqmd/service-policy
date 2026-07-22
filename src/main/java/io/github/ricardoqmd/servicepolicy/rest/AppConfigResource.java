package io.github.ricardoqmd.servicepolicy.rest;

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

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfig;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.PipConfig;
import io.github.ricardoqmd.servicepolicy.problem.AppConfigNotFoundException;
import io.github.ricardoqmd.servicepolicy.problem.ForbiddenProblemException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionRequiredException;
import io.quarkus.security.Authenticated;

/**
 * PAP-facing per-application configuration endpoints (ADR-029): the claim-to-attribute mapping and
 * the attribute-source settings of one application.
 *
 * <p>Configuration is <em>data</em>, not deployment config. ADR-013's global markers stay in
 * {@code application.properties} because they are global and deployment-shaped; what is
 * per-application by nature lives here, so onboarding or reconfiguring an application is an API call
 * rather than a redeploy that restarts every other application's engine.
 *
 * <p>A SINGLETON resource, not a collection: one document per app, addressed by the path alone.
 * There is no list endpoint because there is nothing to list within an application.
 *
 * <p>Admin-gated (ADR-013 §4) and nested under its application (ADR-026), so {@code app} comes from
 * the path and must not appear in any body. Conditional writes follow the same contract as
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
 * <p><strong>Nothing consumes this configuration yet.</strong> The claim mapping and the PIP adapter
 * are later ADRs; ADR-029 makes the document administrable and stops there. {@code /evaluate} is
 * untouched by this resource.
 */
@Path("/v1/apps/{app}/configuration")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "configuration", description = "PAP-facing per-application configuration (ADR-029).")
@Authenticated
public class AppConfigResource {

    private final AppConfigStore configStore;
    private final AuthContext authContext;
    private final ServicePolicyConfig cfg;

    AppConfigResource(AppConfigStore configStore, AuthContext authContext, ServicePolicyConfig cfg) {
        this.configStore = configStore;
        this.authContext = authContext;
        this.cfg = cfg;
    }

    @POST
    @Operation(
            summary = "Create the configuration of an application",
            description = "Creates this app's configuration document (ADR-029) at revision 1. Requires the admin"
                    + " marker. The body must NOT carry an 'app' field — it is determined by the path"
                    + " (ADR-026). At least one of 'subjectAttributes' or 'pip' must be present; when 'pip'"
                    + " is present all four of its fields are required, its 'url' must be an absolute"
                    + " http/https URL containing the '{sub}' placeholder, and 'credentialRef' is a"
                    + " REFERENCE to a credential — secrets are never stored in this document, only the name"
                    + " the deployment's secret mechanism resolves. The configured URL is never contacted at"
                    + " write time. Returns 400 INVALID_APP_CONFIG listing every offending field, 409"
                    + " APP_CONFIG_ALREADY_EXISTS if the app already has a configuration (use PUT).")
    public Response create(@PathParam("app") String app, AppConfigWrite body) {
        requireAdmin();
        requireBody(body);

        AppConfig created = configStore.create(app, toDraft(body), authContext.callerSubject());
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
                    + " answer only, since an app without configuration evaluates normally (ADR-029).")
    public Response get(@PathParam("app") String app) {
        requireAdmin();
        AppConfig config = configStore.find(app).orElseThrow(() -> new AppConfigNotFoundException(app));
        return Response.ok(view(config)).tag(etag(config)).build();
    }

    @PUT
    @Operation(
            summary = "Replace the configuration of an application",
            description = "Replaces the FULL configuration document (ADR-029) — this is a replace, not a merge,"
                    + " so a section omitted from the body is removed. Requires the admin marker and an"
                    + " If-Match header with the current ETag. Same body rules and same 'credentialRef'"
                    + " contract as create: secrets are never stored, only the reference. Returns 428 if"
                    + " If-Match is absent or unparseable, 412 if stale (checked before the body is"
                    + " validated), 404 if the app has no configuration, 400 INVALID_APP_CONFIG listing"
                    + " every offending field. A body that does not parse at all — absent, malformed, or"
                    + " carrying an unknown field — is a 400 before the precondition is evaluated: there is"
                    + " no document yet to check one against.")
    public Response replace(
            @PathParam("app") String app, @HeaderParam("If-Match") String ifMatch, AppConfigWrite body) {
        requireAdmin();
        long revision = parseIfMatch(ifMatch);
        requireBody(body);

        AppConfig replaced = configStore.replace(app, toDraft(body), revision, authContext.callerSubject());
        return Response.ok(view(replaced)).tag(etag(replaced)).build();
    }

    @DELETE
    @Operation(
            summary = "Delete the configuration of an application",
            description = "Removes this app's configuration (ADR-029). Requires the admin marker and an If-Match"
                    + " header with the current ETag. Unguarded by design: the app returns to evaluating on"
                    + " caller-asserted attributes alone, which withdraws derived capability and can never"
                    + " widen a decision. Returns 428 if If-Match is absent or unparseable, 412 if stale, 404"
                    + " if the app has no configuration, 204 on success. There is no body, so the transport"
                    + " boundary has nothing to reject and the conditional request is evaluated first.")
    public Response delete(@PathParam("app") String app, @HeaderParam("If-Match") String ifMatch) {
        requireAdmin();
        long revision = parseIfMatch(ifMatch);

        configStore.delete(app, revision);
        return Response.noContent().build();
    }

    private void requireAdmin() {
        if (!authContext.has(cfg.authz().admin())) {
            throw new ForbiddenProblemException("admin marker required.");
        }
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
