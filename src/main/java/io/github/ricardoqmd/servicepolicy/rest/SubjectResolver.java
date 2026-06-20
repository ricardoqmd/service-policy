package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Base64;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resolves the subject identifier from an HTTP {@code Authorization} header.
 *
 * <p>Reads the {@code sub} claim from the JWT payload (fallback: {@code preferred_username},
 * else {@code "unknown"}) without performing signature verification.
 *
 * <p><strong>STUB ONLY</strong> — real token validation via {@code quarkus-oidc} + JWKS endpoint
 * is introduced in Phase 3 (ADR-003). This class will be removed at that point.
 */
@ApplicationScoped
public class SubjectResolver {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Resolves the subject from the given {@code Authorization} header value.
     *
     * @param authorizationHeader the raw value of the HTTP {@code Authorization} header.
     * @return the resolved subject string (never null).
     * @throws WebApplicationException HTTP 401 if the header is missing, blank, or malformed.
     */
    public String resolve(String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.isBlank()
                || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized("Missing or invalid Authorization header.");
        }

        String token = authorizationHeader.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw unauthorized("JWT must have at least two segments.");
        }

        try {
            byte[] decoded = base64UrlDecode(parts[1]);
            JsonNode claims = objectMapper.readTree(decoded);

            if (claims.hasNonNull("sub") && !claims.get("sub").asText().isBlank()) {
                return claims.get("sub").asText();
            }
            if (claims.hasNonNull("preferred_username")
                    && !claims.get("preferred_username").asText().isBlank()) {
                return claims.get("preferred_username").asText();
            }
            return "unknown";
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized("Unable to decode JWT payload.");
        }
    }

    private static WebApplicationException unauthorized(String message) {
        return new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiError("UNAUTHORIZED", message))
                .build());
    }

    private static byte[] base64UrlDecode(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(s + "=".repeat(pad));
    }
}
