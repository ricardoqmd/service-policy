package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.time.Instant;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;

/**
 * End-to-end tests for permission enumeration (ADR-030): {@code GET /v1/apps/{app}/permissions}, the
 * three outcomes, {@code dependsOn}, degradation, isolation, and the ETag/304 caching contract.
 *
 * <p>Determinism here is driven by references that resolve without a resource instance <em>and</em>
 * without token claims — {@code subject.id} (the caller) and {@code resource.type} (the pair). Token
 * claims cannot be injected over HTTP in the test profile (OIDC is disabled), so the claim →
 * attribute → deterministic-outcome path is covered directly at the deriver/evaluator seam by
 * {@code EnumerationClaimDrivenTest}; here every {@code subject.attr} reference is unresolved, which is
 * itself the degradation case (unresolved subject attribute → conditional, never omitted).
 *
 * <p>Each test uses its own {@code app} so the singleton {@code PermissionsCache}, keyed by
 * {@code (app, subject)} and outliving a test, never serves one test's result to another. Within a
 * test the same {@code (app, subject)} is what makes a second GET a cache hit.
 */
@QuarkusTest
class PermissionsResourceTest {

    private static final String SUBJECT = "alice";

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
    }

    // ── The three outcomes, end to end ───────────────────────────────────────

    @Test
    @TestSecurity(user = SUBJECT)
    void enumeratesTheThreeOutcomesWithExactDependsOnAndOrdering() {
        String app = "perm-outcomes";
        seed(app);

        String generatedAt = given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .body("app", equalTo(app))
                .body("subject", equalTo(SUBJECT))
                // create (deterministic, subject.id), delete (deterministic, resource.type),
                // share (conditional on an unresolved subject attribute), update (conditional on a
                // resource attribute). read has no policy and submit is a deterministic deny: absent.
                .body("permissions", hasSize(4))
                .body("permissions.action", contains("create", "delete", "share", "update"))
                .body("permissions.resourceType", contains("document", "document", "document", "document"))
                .body("permissions[0].conditional", equalTo(false))
                .body("permissions[1].conditional", equalTo(false))
                .body("permissions[2].conditional", equalTo(true))
                .body("permissions[3].conditional", equalTo(true))
                // dependsOn only ever names resource attributes: update carries areaId; share, whose
                // indeterminacy is a subject attribute, carries none — and an unconditional entry omits
                // the KEY entirely (asserted as key-absence, not a null value).
                .body("permissions[3].dependsOn", contains("areaId"))
                .body("permissions[0]", not(hasKey("dependsOn")))
                .body("permissions[1]", not(hasKey("dependsOn")))
                .body("permissions[2]", not(hasKey("dependsOn")))
                .extract()
                .path("generatedAt");

        // generatedAt is a real RFC 3339 UTC instant, not merely non-null.
        assertDoesNotThrow(() -> Instant.parse(generatedAt), "generatedAt must be a parseable RFC 3339 instant");
    }

    @Test
    @TestSecurity(user = SUBJECT)
    void anAppWithNoCatalogueEnumeratesToAnEmptyList() {
        given().when()
                .get("/v1/apps/{app}/permissions", "perm-no-catalogue")
                .then()
                .statusCode(200)
                .body("subject", equalTo(SUBJECT))
                .body("permissions", hasSize(0))
                .body("generatedAt", not(nullValue()));
    }

    /** An applicable policy that depends on an unresolved subject attribute leaves the pair conditional, not omitted. */
    @Test
    @TestSecurity(user = SUBJECT)
    void anUnresolvedSubjectAttributeDegradesToConditionalNotOmitted() {
        String app = "perm-degraded";
        seed(app);

        given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .body("permissions.find { it.action == 'share' }.conditional", equalTo(true))
                .body("permissions.find { it.action == 'share' }", not(hasKey("dependsOn")));
    }

    // ── Self-only, auth, isolation ───────────────────────────────────────────

    @Test
    void unauthenticatedReturns401() {
        given().when().get("/v1/apps/{app}/permissions", "perm-auth").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = SUBJECT)
    void theSubjectIsAlwaysTheCallersOwn() {
        String app = "perm-self";
        seed(app);

        given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .body("subject", equalTo(SUBJECT));
    }

    @Test
    @TestSecurity(user = SUBJECT)
    void anotherAppsPairsNeverAppear() {
        String app = "perm-isolation";
        seed(app);
        // A different app with a different vocabulary and an always-permit policy.
        ActionCatalogueTestSupport.declare(catalogueRepository, "perm-isolation-other", "secret", "peek");
        activate(
                "perm-isolation-other",
                policy("p-peek", "secret", List.of("peek"), Effect.DENY, rule(Effect.PERMIT, alwaysTrue())));

        given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                // Only this app's document pairs; 'secret'/'peek' from the other app cannot leak in.
                .body("permissions.resourceType", not(contains("secret")))
                .body("permissions.action", not(contains("peek")));
    }

    // ── ETag / 304 caching ───────────────────────────────────────────────────

    @Test
    @TestSecurity(user = SUBJECT)
    void repeatedGetsShareAStableEtagAndRevalidateWith304() {
        String app = "perm-etag";
        seed(app);

        String etag = given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .header("ETag", not(nullValue()))
                // Token order is not fixed by the framework; both must be present, aligned with the TTL.
                .header("Cache-Control", allOf(containsString("private"), containsString("max-age=30")))
                .extract()
                .header("ETag");

        // Same inputs → same validator on a second GET.
        given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .header("ETag", equalTo(etag));

        // If-None-Match with the current ETag → 304, same ETag, same Cache-Control, no body.
        given().header("If-None-Match", etag)
                .when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(304)
                .header("ETag", equalTo(etag))
                .header("Cache-Control", allOf(containsString("private"), containsString("max-age=30")))
                .body(equalTo(""));
    }

    /** A stale or foreign ETag does not match the current result → a full 200 with a body, not a 304. */
    @Test
    @TestSecurity(user = SUBJECT)
    void aStaleOrForeignIfNoneMatchGetsAFresh200() {
        String app = "perm-stale-etag";
        seed(app);

        given().header("If-None-Match", "\"0000000000000000000000000000000000000000000000000000000000000000\"")
                .when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .header("ETag", not(nullValue()))
                .body("permissions", hasSize(4));
    }

    /**
     * Within the 30-second TTL a change behind the cache's back is not visible — the documented
     * staleness window (ADR-030 §5). The response is advisory and enforcement re-checks, so a briefly
     * stale hint is acceptable by design. What is <em>not</em> asserted here is the post-TTL refresh
     * over HTTP, which would need a 30-second wall-clock wait; the TTL-expiry and eviction <em>code
     * paths</em> themselves are covered directly, with a tiny injected TTL, in {@code PermissionsCacheTest}.
     */
    @Test
    @TestSecurity(user = SUBJECT)
    void aChangeBehindTheCacheIsNotVisibleWithinTheTtl() {
        String app = "perm-stale";
        seed(app);

        // First GET populates the cache with 'create' present.
        given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .body("permissions.action", contains("create", "delete", "share", "update"));

        // Deactivate the create policy directly — the cache is not invalidated (no write-through, v1).
        long revision = lifecycleStore.findHead(app, "p-create").orElseThrow().revision();
        lifecycleStore.deactivate(app, "p-create", revision, "seed", null);

        // Still served from cache: 'create' is present though its policy is now inactive.
        given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .body("permissions.action", contains("create", "delete", "share", "update"));
    }

    // ── Agreement with enforcement ───────────────────────────────────────────

    /**
     * For a fully type-level policy set, enumeration and enforcement agree where both are
     * deterministic: an enumerated {@code conditional:false} pair evaluates to {@code allowed:true} at
     * {@code /evaluate}, and an omitted pair to {@code allowed:false}.
     */
    @Test
    @TestSecurity(user = SUBJECT)
    void enumerationAndEnforcementAgreeWhereBothAreDeterministic() {
        String app = "perm-agreement";
        ActionCatalogueTestSupport.declare(catalogueRepository, app, "document", "create", "read");
        // create permits deterministically (subject.id), read is a deterministic deny.
        activate(
                app,
                policy(
                        "p-create",
                        "document",
                        List.of("create"),
                        Effect.DENY,
                        rule(
                                Effect.PERMIT,
                                new Comparison(Operator.EQ, new AttributeRef("subject.id"), new Literal(SUBJECT)))));
        activate(app, policy("p-read", "document", List.of("read"), Effect.DENY, rule(Effect.DENY, alwaysTrue())));

        // Enumeration: create present & unconditional, read omitted.
        given().when()
                .get("/v1/apps/{app}/permissions", app)
                .then()
                .statusCode(200)
                .body("permissions.action", contains("create"))
                .body("permissions[0].conditional", equalTo(false));

        // Enforcement agrees: create allowed, read denied.
        evaluate(app, "document:create").body("allowed", equalTo(true));
        evaluate(app, "document:read").body("allowed", equalTo(false));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private ValidatableResponse evaluate(String app, String action) {
        return given().contentType("application/json")
                .body("{\"action\": \"%s\", \"resource\": {\"type\": \"document\", \"id\": \"d1\"}}".formatted(action))
                .when()
                .post("/v1/apps/{app}/evaluate", app)
                .then()
                .statusCode(200);
    }

    /**
     * Seeds the four-outcome document fixture (plus the omitted request/submit): create and delete
     * deterministic via claim-independent references, update conditional on a resource attribute,
     * share conditional on an unresolved subject attribute, read with no policy, submit a
     * deterministic deny.
     */
    private void seed(String app) {
        ActionCatalogueTestSupport.declare(
                catalogueRepository, app, "document", "create", "read", "update", "delete", "share");
        ActionCatalogueTestSupport.declare(catalogueRepository, app, "request", "submit");

        activate(
                app,
                policy(
                        "p-create",
                        "document",
                        List.of("create"),
                        Effect.DENY,
                        rule(
                                Effect.PERMIT,
                                new Comparison(Operator.EQ, new AttributeRef("subject.id"), new Literal(SUBJECT)))));
        activate(
                app,
                policy(
                        "p-delete",
                        "document",
                        List.of("delete"),
                        Effect.DENY,
                        rule(
                                Effect.PERMIT,
                                new Comparison(
                                        Operator.EQ, new AttributeRef("resource.type"), new Literal("document")))));
        activate(
                app,
                policy(
                        "p-update",
                        "document",
                        List.of("update"),
                        Effect.DENY,
                        rule(
                                Effect.PERMIT,
                                new Comparison(
                                        Operator.EQ, new AttributeRef("resource.attr.areaId"), new Literal("x")))));
        activate(
                app,
                policy(
                        "p-share",
                        "document",
                        List.of("share"),
                        Effect.DENY,
                        rule(
                                Effect.PERMIT,
                                new Comparison(
                                        Operator.EQ, new AttributeRef("subject.attr.area"), new Literal("north")))));
        activate(app, policy("p-submit", "request", List.of("submit"), Effect.DENY, rule(Effect.DENY, alwaysTrue())));
    }

    private void activate(String app, Policy policy) {
        lifecycleStore.create(app, policy, "seed", null);
        lifecycleStore.activate(app, policy.id(), 1, 0L, "seed", null);
    }

    private static Policy policy(
            String id, String resourceType, List<String> actions, Effect defaultEffect, Rule rule) {
        return new Policy(
                id, 1, resourceType, actions, CombiningAlgorithm.DENY_OVERRIDES, defaultEffect, List.of(rule));
    }

    private static Rule rule(Effect effect, Condition condition) {
        return new Rule("r-" + effect, effect, condition);
    }

    private static Condition alwaysTrue() {
        return new Comparison(Operator.EQ, new Literal(1), new Literal(1));
    }
}
