package io.github.ricardoqmd.servicepolicy.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Permission;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.WebApplicationException;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.quarkus.security.credential.Credential;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;

/**
 * Unit tests for {@link AuthContext} — no Quarkus container, plain JUnit.
 * Verifies the caller-subject resolution chain, authorization-marker checks,
 * and the hybrid subject-provenance rule (ADR-013 §5).
 */
class AuthContextTest {

    // ── callerSubject() ──────────────────────────────────────────────────────

    @Test
    void callerSubjectReturnsJwtSubWhenPresent() {
        AuthContext ctx = new AuthContext(jwtIdentity("alice", null, false), anyConfig());
        assertEquals("alice", ctx.callerSubject());
    }

    @Test
    void callerSubjectFallsBackToPreferredUsernameWhenSubIsBlank() {
        AuthContext ctx = new AuthContext(jwtIdentity("  ", "alice.login", false), anyConfig());
        assertEquals("alice.login", ctx.callerSubject());
    }

    @Test
    void callerSubjectFallsBackToPrincipalNameWhenPrincipalIsNotJwt() {
        AuthContext ctx = new AuthContext(plainIdentity("alice", false), anyConfig());
        assertEquals("alice", ctx.callerSubject());
    }

    @Test
    void callerSubjectThrows401WhenNoUsableIdentityFound() {
        AuthContext ctx = new AuthContext(plainIdentity(null, false), anyConfig());
        WebApplicationException ex = assertThrows(WebApplicationException.class, ctx::callerSubject);
        assertEquals(401, ex.getResponse().getStatus());
        assertEquals("UNAUTHORIZED", ((ApiError) ex.getResponse().getEntity()).error());
    }

    // ── has(Marker) ──────────────────────────────────────────────────────────

    @Test
    void hasReturnsTrueWhenRoleModeAndIdentityHoldsRole() {
        AuthContext ctx = new AuthContext(plainIdentity("u", true), anyConfig());
        assertTrue(ctx.has(marker(ServicePolicyConfig.Mode.ROLE, Optional.of("admin-role"), Optional.empty())));
    }

    @Test
    void hasReturnsFalseWhenRoleModeAndIdentityLacksRole() {
        AuthContext ctx = new AuthContext(plainIdentity("u", false), anyConfig());
        assertFalse(ctx.has(marker(ServicePolicyConfig.Mode.ROLE, Optional.of("admin-role"), Optional.empty())));
    }

    @Test
    void hasReturnsTrueWhenScopeModeAndScopeClaimContainsValue() {
        AuthContext ctx = new AuthContext(jwtIdentityWithScope("u", "read pdp-client write"), anyConfig());
        assertTrue(ctx.has(marker(ServicePolicyConfig.Mode.SCOPE, Optional.empty(), Optional.of("pdp-client"))));
    }

    @Test
    void hasReturnsFalseWhenScopeModeAndScopeClaimIsAbsent() {
        AuthContext ctx = new AuthContext(jwtIdentityWithScope("u", null), anyConfig());
        assertFalse(ctx.has(marker(ServicePolicyConfig.Mode.SCOPE, Optional.empty(), Optional.of("pdp-client"))));
    }

    // ── resolveEffectiveSubject() ─────────────────────────────────────────────

    @Test
    void resolveEffectiveSubjectReturnsSelfWhenRequestedIsNull() {
        AuthContext ctx = new AuthContext(plainIdentity("caller", false), anyConfig());
        assertEquals("caller", ctx.resolveEffectiveSubject(null));
    }

    @Test
    void resolveEffectiveSubjectReturnsSelfWhenRequestedEqualsCallerSubject() {
        AuthContext ctx = new AuthContext(plainIdentity("caller", false), anyConfig());
        assertEquals("caller", ctx.resolveEffectiveSubject("caller"));
    }

    @Test
    void resolveEffectiveSubjectThrows403WhenSubjectDiffersAndDelegationMarkerAbsent() {
        AuthContext ctx = new AuthContext(
                plainIdentity("caller", false),
                delegationConfig(ServicePolicyConfig.Mode.ROLE, Optional.of("pdp-client"), Optional.empty()));
        WebApplicationException ex =
                assertThrows(WebApplicationException.class, () -> ctx.resolveEffectiveSubject("other-user"));
        assertEquals(403, ex.getResponse().getStatus());
        assertEquals("FORBIDDEN", ((ApiError) ex.getResponse().getEntity()).error());
    }

    @Test
    void resolveEffectiveSubjectReturnsDelegatedSubjectWhenDelegationMarkerPresent() {
        AuthContext ctx = new AuthContext(
                plainIdentity("caller", true),
                delegationConfig(ServicePolicyConfig.Mode.ROLE, Optional.of("pdp-client"), Optional.empty()));
        assertEquals("other-user", ctx.resolveEffectiveSubject("other-user"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ServicePolicyConfig anyConfig() {
        return delegationConfig(ServicePolicyConfig.Mode.ROLE, Optional.of("pdp-client"), Optional.empty());
    }

    private static ServicePolicyConfig delegationConfig(
            ServicePolicyConfig.Mode mode, Optional<String> role, Optional<String> scope) {
        ServicePolicyConfig.Marker del = marker(mode, role, scope);
        return new ServicePolicyConfig() {
            @Override
            public Info info() {
                return null;
            }

            @Override
            public Authz authz() {
                return new Authz() {
                    @Override
                    public ServicePolicyConfig.Marker admin() {
                        return del;
                    }

                    @Override
                    public ServicePolicyConfig.Marker delegation() {
                        return del;
                    }
                };
            }
        };
    }

    private static ServicePolicyConfig.Marker marker(
            ServicePolicyConfig.Mode mode, Optional<String> role, Optional<String> scope) {
        return new ServicePolicyConfig.Marker() {
            @Override
            public ServicePolicyConfig.Mode mode() {
                return mode;
            }

            @Override
            public Optional<String> role() {
                return role;
            }

            @Override
            public Optional<String> scope() {
                return scope;
            }
        };
    }

    private static SecurityIdentity jwtIdentity(String sub, String preferredUsername, boolean hasRole) {
        return new SecurityIdentity() {
            @Override
            public java.security.Principal getPrincipal() {
                return new JsonWebToken() {
                    @Override
                    public String getName() {
                        return sub;
                    }

                    @Override
                    public String getRawToken() {
                        return "";
                    }

                    @Override
                    public String getIssuer() {
                        return "";
                    }

                    @Override
                    public Set<String> getAudience() {
                        return Set.of();
                    }

                    @Override
                    public String getSubject() {
                        return sub;
                    }

                    @Override
                    public String getTokenID() {
                        return "";
                    }

                    @Override
                    public long getExpirationTime() {
                        return 0;
                    }

                    @Override
                    public long getIssuedAtTime() {
                        return 0;
                    }

                    @Override
                    public Set<String> getClaimNames() {
                        return Set.of();
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getClaim(String claimName) {
                        if ("preferred_username".equals(claimName)) {
                            return (T) preferredUsername;
                        }
                        return null;
                    }
                };
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }

            @Override
            public Set<String> getRoles() {
                return Set.of();
            }

            @Override
            public boolean hasRole(String role) {
                return hasRole;
            }

            @Override
            public Set<Permission> getPermissions() {
                return Set.of();
            }

            @Override
            public <T extends Credential> T getCredential(Class<T> type) {
                return null;
            }

            @Override
            public Set<Credential> getCredentials() {
                return Set.of();
            }

            @Override
            public <T> T getAttribute(String name) {
                return null;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Map.of();
            }

            @Override
            public Uni<Boolean> checkPermission(Permission permission) {
                return Uni.createFrom().item(false);
            }
        };
    }

    private static SecurityIdentity jwtIdentityWithScope(String name, String scope) {
        return new SecurityIdentity() {
            @Override
            public java.security.Principal getPrincipal() {
                return new JsonWebToken() {
                    @Override
                    public String getName() {
                        return name;
                    }

                    @Override
                    public String getRawToken() {
                        return "";
                    }

                    @Override
                    public String getIssuer() {
                        return "";
                    }

                    @Override
                    public Set<String> getAudience() {
                        return Set.of();
                    }

                    @Override
                    public String getSubject() {
                        return name;
                    }

                    @Override
                    public String getTokenID() {
                        return "";
                    }

                    @Override
                    public long getExpirationTime() {
                        return 0;
                    }

                    @Override
                    public long getIssuedAtTime() {
                        return 0;
                    }

                    @Override
                    public Set<String> getClaimNames() {
                        return Set.of();
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getClaim(String claimName) {
                        if ("scope".equals(claimName)) {
                            return (T) scope;
                        }
                        return null;
                    }
                };
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }

            @Override
            public Set<String> getRoles() {
                return Set.of();
            }

            @Override
            public boolean hasRole(String role) {
                return false;
            }

            @Override
            public Set<Permission> getPermissions() {
                return Set.of();
            }

            @Override
            public <T extends Credential> T getCredential(Class<T> type) {
                return null;
            }

            @Override
            public Set<Credential> getCredentials() {
                return Set.of();
            }

            @Override
            public <T> T getAttribute(String name) {
                return null;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Map.of();
            }

            @Override
            public Uni<Boolean> checkPermission(Permission permission) {
                return Uni.createFrom().item(false);
            }
        };
    }

    private static SecurityIdentity plainIdentity(String name, boolean hasRole) {
        return new SecurityIdentity() {
            @Override
            public java.security.Principal getPrincipal() {
                return () -> name;
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }

            @Override
            public Set<String> getRoles() {
                return Set.of();
            }

            @Override
            public boolean hasRole(String role) {
                return hasRole;
            }

            @Override
            public Set<Permission> getPermissions() {
                return Set.of();
            }

            @Override
            public <T extends Credential> T getCredential(Class<T> type) {
                return null;
            }

            @Override
            public Set<Credential> getCredentials() {
                return Set.of();
            }

            @Override
            public <T> T getAttribute(String name) {
                return null;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Map.of();
            }

            @Override
            public Uni<Boolean> checkPermission(Permission permission) {
                return Uni.createFrom().item(false);
            }
        };
    }
}
