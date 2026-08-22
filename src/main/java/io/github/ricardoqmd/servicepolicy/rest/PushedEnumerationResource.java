package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.quarkus.security.Authenticated;

/**
 * Permission enumeration under caller-asserted subject attributes (ADR-032): the second transport for
 * the computation ADR-030 already performs, for deployments whose authorization attributes do not
 * live in the token.
 *
 * <p>This is a sub-resource action ({@code :enumerate}) on the app's permission collection, following
 * {@code POST /v1/apps/&#123;app&#125;/policies:simulate} (ADR-027) — a read whose input does not fit
 * in a URL. It lives on its own resource rather than on {@link PermissionsResource} for the same
 * mechanical reason {@link SimulationResource} does: the {@code permissions:enumerate} suffix is a
 * single path segment, which JAX-RS cannot express as a sub-path of
 * {@code /v1/apps/&#123;app&#125;/permissions} — a method-level {@code @Path(":enumerate")} inserts a
 * separator, and the exact path becomes unreachable. A dedicated resource owns it.
 *
 * <p><strong>The deriver is absent, not merely unused.</strong> ADR-032 §3 requires that
 * {@code SubjectAttributeDeriver} is never consulted on this path: the validated token here belongs to
 * the <em>calling backend's service account</em>, not to the subject being enumerated, so deriving
 * that token's claims and attributing them to a different subject is exactly the attribute forgery
 * ADR-010 exists to prevent. This class holds no reference to the deriver, does not import it and
 * cannot reach it — the rule is enforced by the type system rather than by a comment or a test. There
 * is consequently no merge and no precedence rule: one transport reads claims, this one reads the body.
 *
 * <p>Everything downstream of the inputs is {@link EnumerationResponder}, shared verbatim with the
 * {@code GET}: same {@link PermissionsView}, same strong ETag over the result <em>and</em> the asserted
 * attributes, same {@code Cache-Control}, same {@code 304}. Only the origin of the subject and its
 * attributes differs, which is the entire content of this ADR.
 *
 * <p>Subject provenance is ADR-013 §5 reused unchanged, through
 * {@link AuthContext#resolveEffectiveSubject}: absent or equal to the caller's own {@code sub} → self;
 * different → the delegation marker or 403. App comes from the path (ADR-026); a body carrying
 * {@code app} is rejected with 400 at deserialization, via {@link UnknownPropertyMapper}.
 */
@Path("/v1/apps/{app}/permissions:enumerate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "permissions", description = "Subject permission enumeration endpoint (ADR-030).")
@Authenticated
public class PushedEnumerationResource {

    private final EnumerationResponder responder;
    private final AuthContext authContext;

    PushedEnumerationResource(EnumerationResponder responder, AuthContext authContext) {
        this.responder = responder;
        this.authContext = authContext;
    }

    @POST
    @Operation(
            summary = "Enumerate a subject's permissions under caller-asserted attributes",
            description = "Same computation, same response and same caching as GET /v1/apps/{app}/permissions;"
                    + " only the origin of the inputs differs. Use this transport when the attributes a"
                    + " policy needs are resolved by the calling backend rather than carried in the token"
                    + " — a role chosen per session, for instance, is not a fact the token can state."
                    + " ADVISORY, NOT ENFORCEMENT: the response is a hint for rendering, never an"
                    + " authorization decision; always re-check each action at /evaluate."
                    + " SUBJECT. Absent, or equal to the caller's own sub, enumerates the caller. A"
                    + " different subject requires the delegation marker — the same one /evaluate requires"
                    + " to act on behalf of another subject — else 403."
                    + " ATTRIBUTES. subjectAttributes is the only source on this transport: token claim"
                    + " mapping does NOT run here, because on this path the token belongs to the calling"
                    + " backend, not to the subject being enumerated. There is no merge and no precedence"
                    + " rule. An absent or empty bag is not an error; it resolves fewer conditions, so more"
                    + " pairs come back conditional, never fewer."
                    + " CACHING. Strong ETag over the computed result AND the asserted attributes;"
                    + " revalidate with If-None-Match for 304. Cache-Control is private, max-age=30."
                    + " An 'app' field in the body is rejected with 400 — the scope is the path's.")
    public Response enumerate(
            @PathParam("app") String app,
            EnumerationRequest request,
            @HeaderParam("If-None-Match") String ifNoneMatch) {

        String subject = authContext.resolveEffectiveSubject(request == null ? null : request.subject());

        // The bag is the body's, or empty. Nothing else is consulted, and there is nothing to merge.
        Map<String, Object> assertedAttributes =
                request == null || request.subjectAttributes() == null ? Map.of() : request.subjectAttributes();

        return responder.respond(app, subject, assertedAttributes, ifNoneMatch);
    }
}
