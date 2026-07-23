package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.github.ricardoqmd.servicepolicy.enumeration.CachedPermissions;
import io.github.ricardoqmd.servicepolicy.enumeration.EnumeratedPair;
import io.github.ricardoqmd.servicepolicy.enumeration.EnumerationEvaluator;
import io.github.ricardoqmd.servicepolicy.enumeration.PermissionsCache;
import io.github.ricardoqmd.servicepolicy.enumeration.SubjectAttributeDeriver;
import io.quarkus.security.Authenticated;

/**
 * PEP/UI-facing permission enumeration endpoint (ADR-030).
 *
 * <p>Returns the {@code (resourceType, action)} pairs the authenticated subject can act on within the
 * application, computed three-valued with no resource instance. The subject is the {@code sub} of the
 * validated token (ADR-013), self only — asking for another subject's permissions is a different
 * question, for a different, admin-gated endpoint, not a flag here.
 *
 * <p>This resource is the translation boundary of ADR-030 §4: it consumes the {@code enumeration}
 * package's three-valued result and hands the web the two-state {@code conditional} flag. The
 * {@code INDETERMINATE} value never crosses into a response. The class depends on {@code enumeration}
 * for the computation and derivation; {@code enumeration} never depends back on {@code rest}.
 *
 * <p>Answers are cached per {@code (app, subject)} and revalidated by ETag (see
 * {@link PermissionsCache}); a computation serves both the {@code 200} and any later {@code 304}.
 */
@Path("/v1/apps/{app}/permissions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "permissions", description = "Subject permission enumeration endpoint (ADR-030).")
@Authenticated
public class PermissionsResource {

    private final EnumerationEvaluator evaluator;
    private final SubjectAttributeDeriver deriver;
    private final PermissionsCache cache;
    private final AuthContext authContext;

    PermissionsResource(
            EnumerationEvaluator evaluator,
            SubjectAttributeDeriver deriver,
            PermissionsCache cache,
            AuthContext authContext) {
        this.evaluator = evaluator;
        this.deriver = deriver;
        this.cache = cache;
        this.authContext = authContext;
    }

    @GET
    @Operation(
            summary = "Enumerate the authenticated subject's permissions",
            description = "ADVISORY, NOT ENFORCEMENT. The response is a hint for rendering, never an authorization"
                    + " decision: a UI that hides a control it should have shown is a usability bug, but a"
                    + " backend that skips /evaluate because this endpoint said yes is a security bug — always"
                    + " enforce per request with attributes the backend trusts (ADR-030 §2)."
                    + " Note subject attributes come from different places on the two paths: enumeration maps"
                    + " them from token claims (ADR-029), while /evaluate uses caller-asserted attributes"
                    + " (ADR-010) — so a conditional=false pair can still be denied at enforcement if the PEP"
                    + " does not assert the attribute the policy needs. Enforcement is the authority and only"
                    + " ever stricter."
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

        CachedPermissions computed = cache.get(app, subject, () -> {
            var derivedAttributes =
                    deriver.derive(app, authContext.callerToken().orElse(null));
            return evaluator.enumerate(app, subject, derivedAttributes);
        });

        EntityTag etag = new EntityTag(unquote(computed.etag()));
        CacheControl cacheControl = new CacheControl();
        cacheControl.setPrivate(true);
        cacheControl.setNoTransform(false); // JAX-RS defaults this true; we do not want it in the header
        cacheControl.setMaxAge(cache.maxAgeSeconds());

        if (matches(ifNoneMatch, computed.etag())) {
            return Response.notModified(etag).cacheControl(cacheControl).build();
        }

        PermissionsView view = new PermissionsView(app, subject, entries(computed.pairs()), computed.generatedAt());
        return Response.ok(view).tag(etag).cacheControl(cacheControl).build();
    }

    private static List<PermissionEntry> entries(List<EnumeratedPair> pairs) {
        return pairs.stream()
                .map(p -> new PermissionEntry(p.resourceType(), p.action(), p.conditional(), p.dependsOn()))
                .toList();
    }

    /** Matches the client's {@code If-None-Match} against the current strong ETag, quotes and all. */
    private static boolean matches(String ifNoneMatch, String currentEtag) {
        return ifNoneMatch != null && ifNoneMatch.trim().equals(currentEtag);
    }

    /** {@link EntityTag} takes the raw value and re-quotes; the stored ETag is already quoted. */
    private static String unquote(String etag) {
        return etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")
                ? etag.substring(1, etag.length() - 1)
                : etag;
    }
}
