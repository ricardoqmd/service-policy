package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.enumeration.PermissionsCache;
import io.github.ricardoqmd.servicepolicy.enumeration.SubjectAttributeDeriver;
import io.quarkus.security.Authenticated;

/**
 * PEP/UI-facing permission enumeration endpoint (ADR-030).
 *
 * <p>Returns the {@code (resourceType, action)} pairs a subject can act on within the application,
 * computed three-valued with no resource instance.
 *
 * <p><strong>Two transports, one computation</strong> (ADR-032). This {@code GET} resolves the subject
 * from the validated token and derives its attributes from that token's claims (ADR-029).
 * {@link PushedEnumerationResource} takes both from a body instead, for deployments whose attributes do
 * not live in the token. Everything after the inputs — the cache, the ETag, the {@code 304} and the
 * view — is {@link EnumerationResponder}, shared by both, so the two responses are identical by
 * construction rather than by convention.
 *
 * <p>This resource is the translation boundary of ADR-030 §4: it consumes the {@code enumeration}
 * package's three-valued result and hands the web the two-state {@code conditional} flag. The
 * {@code INDETERMINATE} value never crosses into a response. The class depends on {@code enumeration}
 * for the derivation; {@code enumeration} never depends back on {@code rest}.
 *
 * <p>Answers are cached per {@code (app, subject, attributes)} and revalidated by ETag (see
 * {@link PermissionsCache}); a computation serves both the {@code 200} and any later {@code 304}. The
 * attributes are in the key and in the ETag because the same subject under two different attribute
 * sets has two different menus (ADR-032 §4) — on this transport, two tokens for one subject whose
 * claims map differently.
 */
@Path("/v1/apps/{app}/permissions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "permissions", description = "Subject permission enumeration endpoint (ADR-030).")
@Authenticated
public class PermissionsResource {

    private final EnumerationResponder responder;
    private final SubjectAttributeDeriver deriver;
    private final AuthContext authContext;

    PermissionsResource(EnumerationResponder responder, SubjectAttributeDeriver deriver, AuthContext authContext) {
        this.responder = responder;
        this.deriver = deriver;
        this.authContext = authContext;
    }

    @GET
    @Operation(
            summary = "Enumerate the authenticated subject's permissions",
            description = "ADVISORY, NOT ENFORCEMENT. The response is a hint for rendering, never an authorization"
                    + " decision: a UI that hides a control it should have shown is a usability bug, but a"
                    + " backend that skips /evaluate because this endpoint said yes is a security bug — always"
                    + " enforce per request with attributes the backend trusts (ADR-030 §2)."
                    + " Note subject attributes come from different places on the two paths: THIS endpoint maps"
                    + " them from token claims (ADR-029), while /evaluate uses caller-asserted attributes"
                    + " (ADR-010) — so a conditional=false pair can still be denied at enforcement if the PEP"
                    + " does not assert the attribute the policy needs. Enforcement is the authority and only"
                    + " ever stricter. A PEP that asserts attributes should enumerate through"
                    + " POST /v1/apps/{app}/permissions:enumerate instead (ADR-032), which takes the same bag"
                    + " this endpoint cannot, so both of its calls resolve attributes identically."
                    + " THREE OUTCOMES. For every (resourceType, action) in the app's catalogue (ADR-028) the"
                    + " active policies are evaluated with no resource instance: a deterministic deny is"
                    + " omitted; a deterministic permit is listed with conditional=false; a decision that"
                    + " depends on the instance is listed with conditional=true and a dependsOn array naming"
                    + " the resource attributes the client must supply."
                    + " TWO PATHS. Use this endpoint for type-level questions (menus, sections, create"
                    + " buttons); for a conditional pair, call /evaluate (or /evaluate/batch) per instance"
                    + " with resource attributes picked by dependsOn. conditional tells you which path a pair"
                    + " needs."
                    + " CACHING. The response carries a strong ETag over the computed result; revalidate with"
                    + " If-None-Match to get 304 Not Modified when nothing changed. Cache-Control is"
                    + " private, max-age=30."
                    + " DEGRADATION. No catalogue → empty list; no configuration or no claim mapping (ADR-029)"
                    + " → fewer resolvable subject attributes, so more pairs come back conditional, never"
                    + " fewer. Subject is the caller's own token (self only); 401 if unauthenticated.")
    public Response permissions(@PathParam("app") String app, @HeaderParam("If-None-Match") String ifNoneMatch) {
        String subject = authContext.callerSubject();
        // Derivation happens BEFORE the cache lookup on this transport, because the derived map is part
        // of the key: two tokens for one subject can map to different attributes and must not share an
        // entry. It is the cheap half — the enumeration it feeds is what the cache actually saves.
        Map<String, Object> derivedAttributes =
                deriver.derive(app, authContext.callerToken().orElse(null));

        return responder.respond(app, subject, derivedAttributes, ifNoneMatch);
    }
}
