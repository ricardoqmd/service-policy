package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;
import java.util.Map;

import jakarta.inject.Singleton;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;

import io.github.ricardoqmd.servicepolicy.enumeration.CachedPermissions;
import io.github.ricardoqmd.servicepolicy.enumeration.EnumeratedPair;
import io.github.ricardoqmd.servicepolicy.enumeration.EnumerationEvaluator;
import io.github.ricardoqmd.servicepolicy.enumeration.PermissionsCache;

/**
 * The half both enumeration transports share (ADR-032 §1): the cache lookup keyed by the effective
 * attributes, the strong ETag, the {@code 304} decision and the view.
 *
 * <p>It exists so that <em>"the response is identical to the {@code GET}"</em> is a property of the
 * code rather than a promise in two OpenAPI descriptions. {@link PermissionsResource} and
 * {@link PushedEnumerationResource} produce their responses here and nowhere else, so a change to the
 * caching contract or the view cannot reach one transport without reaching the other.
 *
 * <p><strong>It knows nothing about where the inputs came from.</strong> It takes the app, the already
 * resolved effective subject and the already resolved attribute map — never a token, never a request
 * body, and in particular never the {@link
 * io.github.ricardoqmd.servicepolicy.enumeration.SubjectAttributeDeriver}. Provenance is each
 * transport's own business: claims on the {@code GET} (ADR-029), the body on {@code :enumerate}
 * (ADR-010). That is also what lets the pushed transport hold no reference to the deriver at all.
 *
 * <p>A collaborator rather than a helper method on one of the resources: a resource class reaching
 * into another resource class is a coupling no architecture rule catches and every reader questions.
 */
// @Singleton (not @ApplicationScoped): stateless and never intercepted, so no proxy is needed (ADR-009).
@Singleton
class EnumerationResponder {

    private final EnumerationEvaluator evaluator;
    private final PermissionsCache cache;

    EnumerationResponder(EnumerationEvaluator evaluator, PermissionsCache cache) {
        this.evaluator = evaluator;
        this.cache = cache;
    }

    /**
     * Enumerates {@code (app, subject)} under {@code subjectAttributes} and renders the response both
     * transports return: {@code 200} with the {@link PermissionsView}, or {@code 304} when the client's
     * validator still matches — both carrying the ETag and {@code Cache-Control}.
     *
     * @param subjectAttributes the effective attributes, already resolved by the caller; the cache keys
     *     and the ETag hash over them, so two bags for one subject are two results (ADR-032 §4).
     */
    Response respond(String app, String subject, Map<String, Object> subjectAttributes, String ifNoneMatch) {
        CachedPermissions computed =
                cache.get(app, subject, subjectAttributes, () -> evaluator.enumerate(app, subject, subjectAttributes));

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

    /** The ADR-030 §4 translation boundary: three-valued in, the two-state {@code conditional} out. */
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
