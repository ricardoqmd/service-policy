package io.github.ricardoqmd.servicepolicy.enumeration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Singleton;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.github.ricardoqmd.servicepolicy.persistence.AppConfig;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigProvider;

/**
 * Derives a subject's attributes from its token, for enumeration (ADR-030 §1).
 *
 * <p>This is the <em>first consumer</em> of ADR-029's claim mapping. For each
 * {@code attribute → claimPath} the operator configured for the application, it reads the value out
 * of the validated token by walking the claim path. It hardcodes no provider's structure and invents
 * no attribute names — both the attribute names and the claim paths come from configuration — so
 * ADR-010 stands: the engine still does not make up subject attributes, it reads the ones an operator
 * declared, exactly as ADR-013 already reads the admin marker from a configured claim, widened from
 * one claim to several.
 *
 * <p>Degradation is silent and honest (ADR-030 §Consequences): an app with no configuration, or a
 * configuration with no {@code subjectAttributes} section, derives an empty map. Fewer resolvable
 * attributes means more pairs come back {@code conditional}, never fewer permissions and never an
 * error.
 *
 * <p><strong>Enumeration and enforcement read subject attributes from different places, by design.</strong>
 * Here they come from mapped token claims; at {@code /evaluate} they are caller-asserted (ADR-010).
 * So a {@code conditional: false} pair reported here can still be <em>denied</em> at enforcement if
 * the PEP does not assert the subject attribute the policy relies on. That is not a contradiction:
 * this endpoint is advisory (ADR-030 §2), enforcement is the authority, and it can only be stricter,
 * never more permissive.
 *
 * <p><strong>Only claims are read here.</strong> The configured {@code pip} attribute source is not
 * consulted — that adapter is a later ADR. This deriver sees the token and nothing else.
 *
 * <p>What a claim path may resolve to: a scalar (string / number / boolean) becomes the value; an
 * array of scalars becomes a {@code List} in claim order. Anything else — a missing claim, an
 * intermediate that is not an object, an object-valued leaf, an array containing non-scalars — leaves
 * the attribute <em>absent</em> from the map. Absent is never a {@code null} entry and never an
 * error: an attribute that is absent here is one enumeration treats as INDETERMINATE, which the
 * caller may still assert at {@code /evaluate}.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class SubjectAttributeDeriver {

    private static final String PATH_SEPARATOR = "\\.";

    private final AppConfigProvider configProvider;

    SubjectAttributeDeriver(AppConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    /**
     * @param token the caller's validated token, or {@code null} when the principal is not a JWT (no
     *     claims to read — the whole map degrades to empty).
     * @return attribute name → derived value; attributes that do not resolve to a scalar or an array
     *     of scalars are omitted.
     */
    public Map<String, Object> derive(String app, JsonWebToken token) {
        Map<String, String> mapping =
                configProvider.forApp(app).map(AppConfig::subjectAttributes).orElse(null);
        if (mapping == null || mapping.isEmpty() || token == null) {
            return Map.of();
        }

        Map<String, Object> derived = new LinkedHashMap<>();
        mapping.forEach((attribute, claimPath) -> {
            Object value = normalize(navigate(token, claimPath));
            if (value != null) {
                derived.put(attribute, value);
            }
        });
        return derived;
    }

    /** Walks the dotted claim path from the token's root, stepping into nested objects. */
    private static Object navigate(JsonWebToken token, String claimPath) {
        String[] segments = claimPath.split(PATH_SEPARATOR);
        Object current = token.getClaim(segments[0]);
        for (int i = 1; i < segments.length && current != null; i++) {
            current = step(current, segments[i]);
        }
        return current;
    }

    /** One step into {@code current} by key, tolerant of both JSON-P (real OIDC) and plain (test) shapes. */
    private static Object step(Object current, String key) {
        if (current instanceof JsonObject object) {
            return object.get(key);
        }
        if (current instanceof Map<?, ?> map) {
            return map.get(key);
        }
        // An intermediate that is not an object cannot be navigated further: the attribute is absent.
        return null;
    }

    /**
     * Reduces a resolved claim node to a stored value, or {@code null} when it is not a scalar or an
     * array of scalars. Handles JSON-P nodes (a real OIDC token) and plain Java values (a test token)
     * with the same rules.
     */
    private static Object normalize(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof JsonValue jsonValue) {
            return normalizeJson(jsonValue);
        }
        if (node instanceof String || node instanceof Number || node instanceof Boolean) {
            return node;
        }
        if (node instanceof Collection<?> collection) {
            return scalarList(collection);
        }
        // A Map (object leaf) or anything else is not a value this deriver exposes.
        return null;
    }

    private static Object normalizeJson(JsonValue value) {
        return switch (value.getValueType()) {
            case STRING -> ((JsonString) value).getString();
            case NUMBER -> numberOf((JsonNumber) value);
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case ARRAY -> scalarList((JsonArray) value);
            case OBJECT, NULL -> null;
        };
    }

    private static Object numberOf(JsonNumber number) {
        return number.isIntegral() ? number.longValue() : number.doubleValue();
    }

    /** @return a list of the collection's scalar values in order, or {@code null} if any element is not a scalar. */
    private static List<Object> scalarList(Iterable<?> elements) {
        List<Object> scalars = new ArrayList<>();
        for (Object element : elements) {
            Object scalar = scalarOf(element);
            if (scalar == null) {
                return null; // an array of objects (or of nulls) is not an array of scalars
            }
            scalars.add(scalar);
        }
        return List.copyOf(scalars);
    }

    private static Object scalarOf(Object element) {
        if (element instanceof JsonValue jsonValue) {
            return switch (jsonValue.getValueType()) {
                case STRING -> ((JsonString) jsonValue).getString();
                case NUMBER -> numberOf((JsonNumber) jsonValue);
                case TRUE -> Boolean.TRUE;
                case FALSE -> Boolean.FALSE;
                case ARRAY, OBJECT, NULL -> null;
            };
        }
        if (element instanceof String || element instanceof Number || element instanceof Boolean) {
            return element;
        }
        return null;
    }
}
