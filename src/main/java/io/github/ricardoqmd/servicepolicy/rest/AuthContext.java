package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Set;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Request-scoped authorization context populated from the validated OIDC JWT (ADR-013).
 *
 * <p>Provides the resolved caller subject, authorization-marker checks, and the hybrid
 * subject-provenance rule: absent subject → self; explicit subject equal to self → self;
 * explicit subject different from self → requires the delegation marker or 403.
 *
 * <p>JWT claims are accessed through {@code SecurityIdentity.getPrincipal()} rather than direct
 * {@code @Inject JsonWebToken} injection, so the bean remains valid when OIDC is disabled in the
 * test profile (quarkus-test-security supplies the identity without a real OIDC server).
 */
@RequestScoped
public class AuthContext {

    private final SecurityIdentity identity;
    private final ServicePolicyConfig cfg;

    AuthContext(SecurityIdentity identity, ServicePolicyConfig cfg) {
        this.identity = identity;
        this.cfg = cfg;
    }

    /**
     * Returns the caller's subject identifier, resolved from the validated JWT.
     * Tries {@code sub}, then {@code preferred_username}, then the security-identity principal name.
     */
    public String callerSubject() {
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            String sub = jwt.getSubject();
            if (sub != null && !sub.isBlank()) return sub;

            Object pref = jwt.getClaim("preferred_username");
            if (pref instanceof String s && !s.isBlank()) return s;
        }
        String name = identity.getPrincipal().getName();
        if (name != null && !name.isBlank()) return name;

        throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiError("UNAUTHORIZED", "no usable subject identity in token"))
                .build());
    }

    /**
     * Checks whether the caller holds the given authorization marker.
     *
     * @param marker configured marker (admin or delegation).
     * @return {@code true} if the caller's token satisfies the marker condition.
     */
    public boolean has(ServicePolicyConfig.Marker marker) {
        return switch (marker.mode()) {
            case ROLE ->
                identity.hasRole(marker.role()
                        .orElseThrow(() -> new IllegalStateException("role not configured for this marker")));
            case SCOPE ->
                scopes().contains(marker.scope()
                        .orElseThrow(() -> new IllegalStateException("scope not configured for this marker")));
        };
    }

    /**
     * Resolves the effective subject applying the hybrid provenance rule (ADR-013 §5).
     *
     * <ul>
     *   <li>Absent or blank {@code requested} → caller's own subject.
     *   <li>{@code requested} equals the caller's subject → caller's own subject.
     *   <li>{@code requested} differs from the caller's subject → delegation marker required; 403 otherwise.
     * </ul>
     */
    public String resolveEffectiveSubject(String requested) {
        String caller = callerSubject();
        if (requested == null || requested.isBlank()) return caller;
        if (requested.equals(caller)) return caller;
        if (!has(cfg.authz().delegation())) {
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ApiError("FORBIDDEN", "delegation marker required to query a different subject"))
                    .build());
        }
        return requested;
    }

    private Set<String> scopes() {
        if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) return Set.of();
        Object s = jwt.getClaim("scope");
        if (!(s instanceof String str) || str.isBlank()) return Set.of();
        return Set.of(str.trim().split("\\s+"));
    }
}
