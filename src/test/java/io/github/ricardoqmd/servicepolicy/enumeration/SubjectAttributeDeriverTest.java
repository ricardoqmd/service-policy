package io.github.ricardoqmd.servicepolicy.enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonValue;

import org.bson.Document;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDocument;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigProvider;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigRepository;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests {@link SubjectAttributeDeriver} — the first consumer of ADR-029's claim mapping (ADR-030 §B5).
 *
 * <p>Configuration is seeded straight through the repository and the token is a hand-built double, so
 * the test drives the real config-read path and the real claim-navigation logic while controlling
 * exactly what claims exist and in what JSON shape. Both JSON-P nodes (a real OIDC token) and plain
 * Java values (what a test identity yields) are exercised, because the deriver must handle both.
 */
@QuarkusTest
class SubjectAttributeDeriverTest {

    private static final String APP = "deriver-test-app";

    @Inject
    SubjectAttributeDeriver deriver;

    @Inject
    AppConfigRepository repository;

    @Inject
    AppConfigProvider provider;

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
        provider.invalidate(APP);
    }

    // ── Scalars and arrays ───────────────────────────────────────────────────

    @Test
    void mapsPlainScalarClaimsOfEachTypeUnchanged() {
        // A test identity yields plain Java values; the deriver passes scalars through as-is.
        configure(Map.of("area", "dept", "level", "clearance", "active", "enabled"));
        JsonWebToken token = token(Map.of("dept", "engineering", "clearance", 7, "enabled", true));

        Map<String, Object> derived = deriver.derive(APP, token);

        assertEquals("engineering", derived.get("area"));
        assertEquals(7, derived.get("level"));
        assertEquals(true, derived.get("active"));
    }

    @Test
    void normalisesJsonNumbersToLongOrDouble() {
        // A real OIDC token exposes numbers as JsonNumber; integral → long, fractional → double.
        configure(Map.of("level", "clearance", "ratio", "score"));
        JsonWebToken token = token(Map.of("clearance", Json.createValue(7), "score", Json.createValue(1.5)));

        Map<String, Object> derived = deriver.derive(APP, token);

        // Integral → long path, fractional → double path (exact boxing is provider-dependent).
        assertEquals(7L, ((Number) derived.get("level")).longValue());
        assertEquals(1.5, ((Number) derived.get("ratio")).doubleValue());
    }

    @Test
    void mapsAnArrayOfScalarsToAListInOrder() {
        configure(Map.of("roles", "groups"));
        JsonWebToken token = token(Map.of("groups", List.of("reviewer", "author")));

        assertEquals(List.of("reviewer", "author"), deriver.derive(APP, token).get("roles"));
    }

    @Test
    void mapsAJsonArrayOfScalarsPreservingOrder() {
        configure(Map.of("roles", "groups"));
        JsonWebToken token = token(Map.of(
                "groups", Json.createArrayBuilder().add("a").add("b").add("c").build()));

        assertEquals(List.of("a", "b", "c"), deriver.derive(APP, token).get("roles"));
    }

    // ── Nested navigation ────────────────────────────────────────────────────

    @Test
    void navigatesNestedJsonObjects() {
        configure(Map.of("rol", "resource_access.kronia.role"));
        JsonWebToken token = token(Map.of(
                "resource_access",
                Json.createObjectBuilder()
                        .add("kronia", Json.createObjectBuilder().add("role", "capturista"))
                        .build()));

        assertEquals("capturista", deriver.derive(APP, token).get("rol"));
    }

    @Test
    void navigatesNestedPlainMaps() {
        configure(Map.of("rol", "realm.role"));
        JsonWebToken token = token(Map.of("realm", Map.of("role", "admin")));

        assertEquals("admin", deriver.derive(APP, token).get("rol"));
    }

    // ── Everything that leaves the attribute absent ──────────────────────────

    @Test
    void absentClaimYieldsNoEntry() {
        configure(Map.of("area", "dept"));
        assertFalse(deriver.derive(APP, token(Map.of())).containsKey("area"));
    }

    @Test
    void intermediateNonObjectYieldsNoEntry() {
        configure(Map.of("rol", "dept.role")); // dept is a string, cannot be navigated into
        assertTrue(deriver.derive(APP, token(Map.of("dept", "engineering"))).isEmpty());
    }

    @Test
    void objectValuedLeafYieldsNoEntry() {
        configure(Map.of("rol", "resource_access"));
        JsonWebToken token = token(Map.of(
                "resource_access", Json.createObjectBuilder().add("k", "v").build()));

        assertTrue(deriver.derive(APP, token).isEmpty());
    }

    @Test
    void arrayOfObjectsYieldsNoEntry() {
        configure(Map.of("roles", "grants"));
        JsonWebToken token = token(Map.of(
                "grants",
                Json.createArrayBuilder()
                        .add(Json.createObjectBuilder().add("r", "x"))
                        .build()));

        assertTrue(deriver.derive(APP, token).isEmpty());
    }

    // ── The remaining resolution arms (audit closure) ────────────────────────

    /** Mapping present but empty → the whole map degrades to empty, without touching the token. */
    @Test
    void mappingPresentButEmptyYieldsEmptyMap() {
        configure(Map.of());
        assertTrue(deriver.derive(APP, token(Map.of("dept", "engineering"))).isEmpty());
    }

    /** Navigation cut mid-path: the intermediate object exists, the next key does not. */
    @Test
    void missingIntermediateKeyYieldsNoEntry() {
        configure(Map.of("rol", "resource_access.role")); // resource_access exists, has no "role"
        JsonWebToken token = token(Map.of(
                "resource_access", Json.createObjectBuilder().add("other", "x").build()));

        assertTrue(deriver.derive(APP, token).isEmpty());
    }

    /** A leaf that arrives as a plain Map (not a JSON object) is an object value → absent. */
    @Test
    void plainMapLeafYieldsNoEntry() {
        configure(Map.of("rol", "obj"));
        assertTrue(deriver.derive(APP, token(Map.of("obj", Map.of("k", "v")))).isEmpty());
    }

    /** A JSON-P boolean leaf resolves to a Boolean. */
    @Test
    void jsonBooleanScalarResolves() {
        configure(Map.of("enabled", "flag"));
        assertEquals(
                true, deriver.derive(APP, token(Map.of("flag", JsonValue.TRUE))).get("enabled"));
        assertEquals(
                false,
                deriver.derive(APP, token(Map.of("flag", JsonValue.FALSE))).get("enabled"));
    }

    /** Arrays of non-string scalars — numbers, booleans — resolve to lists in order. */
    @Test
    void jsonArraysOfNumbersAndBooleansResolveToLists() {
        configure(Map.of("levels", "nums", "flags", "bools"));
        JsonWebToken token = token(Map.of(
                "nums", Json.createArrayBuilder().add(1).add(2).build(),
                "bools", Json.createArrayBuilder().add(true).add(false).build()));

        Map<String, Object> derived = deriver.derive(APP, token);
        // The number array resolves to a list of numbers (exact boxing is provider-dependent).
        List<?> levels = (List<?>) derived.get("levels");
        assertEquals(2, levels.size());
        assertEquals(1L, ((Number) levels.get(0)).longValue());
        assertEquals(2L, ((Number) levels.get(1)).longValue());
        assertEquals(List.of(true, false), derived.get("flags"));
    }

    /** An array whose element is itself an array is not an array of scalars → absent. */
    @Test
    void jsonArrayContainingANonScalarYieldsNoEntry() {
        configure(Map.of("roles", "nested"));
        JsonWebToken token = token(Map.of(
                "nested",
                Json.createArrayBuilder()
                        .add(Json.createArrayBuilder().add("x"))
                        .build()));

        assertTrue(deriver.derive(APP, token).isEmpty());
    }

    @Test
    void onlyResolvableAttributesAppearTheRestAreAbsentNotNull() {
        configure(Map.of("area", "dept", "missing", "nope"));
        Map<String, Object> derived = deriver.derive(APP, token(Map.of("dept", "engineering")));

        assertEquals(1, derived.size());
        assertEquals("engineering", derived.get("area"));
        assertNull(derived.get("missing"));
        assertFalse(derived.containsKey("missing"));
    }

    // ── Degradation ──────────────────────────────────────────────────────────

    @Test
    void noConfigurationYieldsEmptyMap() {
        assertTrue(deriver.derive(APP, token(Map.of("dept", "engineering"))).isEmpty());
    }

    @Test
    void configurationWithoutSubjectAttributesYieldsEmptyMap() {
        AppConfigDocument document = new AppConfigDocument();
        document.app = APP;
        document.subjectAttributes = null;
        document.pip = null;
        document.revision = 1L;
        document.audit = new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
        repository.persist(document);
        provider.invalidate(APP);

        assertTrue(deriver.derive(APP, token(Map.of("dept", "x"))).isEmpty());
    }

    @Test
    void aNullTokenYieldsEmptyMap() {
        configure(Map.of("area", "dept"));
        assertTrue(deriver.derive(APP, null).isEmpty());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void configure(Map<String, String> subjectAttributes) {
        AppConfigDocument document = new AppConfigDocument();
        document.app = APP;
        Document mapping = new Document();
        subjectAttributes.forEach(mapping::append);
        document.subjectAttributes = mapping;
        document.pip = null;
        document.revision = 1L;
        document.audit = new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
        repository.persist(document);
        provider.invalidate(APP);
    }

    private static JsonWebToken token(Map<String, Object> claims) {
        return new FakeJsonWebToken(claims);
    }
}
