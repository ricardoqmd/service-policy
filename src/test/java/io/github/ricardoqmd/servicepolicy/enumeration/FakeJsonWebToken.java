package io.github.ricardoqmd.servicepolicy.enumeration;

import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * A minimal {@link JsonWebToken} for tests: only the three abstract methods, claims from a map.
 *
 * <p>Used to drive {@link SubjectAttributeDeriver} directly. Claim injection through the HTTP layer
 * would need a running OIDC identity, which the test profile disables — and the deriver's contract is
 * exactly "given a token, read these claims", so a token double tests it faithfully and lets a claim
 * be any JSON-P node or plain value the deriver must handle.
 */
record FakeJsonWebToken(Map<String, Object> claims) implements JsonWebToken {

    @Override
    public String getName() {
        return "alice";
    }

    @Override
    public Set<String> getClaimNames() {
        return claims.keySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
        return (T) claims.get(claimName);
    }
}
