package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/**
 * End-to-end tests for {@code POST /v1/apps/{app}/permissions:enumerate} (ADR-032): the second
 * transport for the enumeration ADR-030 already computes, where the subject and its attributes come
 * from the body instead of the token.
 *
 * <p>The fixture is one policy whose rule reads {@code subject.attr.area}. That single reference makes
 * every property here observable end to end: asserting {@code area=north} resolves it to a
 * deterministic permit, {@code area=south} to a deterministic deny (so the pair is omitted), and
 * asserting nothing leaves it unresolved (so the pair is conditional). Three different bodies for one
 * {@code (app, subject)} — which is exactly what the cache and the ETag must tell apart.
 *
 * <p>Each test uses its own {@code app} so the singleton cache, which outlives a test, never serves
 * one test's result to another.
 */
@QuarkusTest
class PushedEnumerationResourceTest {

    private static final String CALLER = "service-account-backend";
    private static final String OTHER_SUBJECT = "carla";
    private static final String DELEGATION_ROLE = "pdp-client";

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @BeforeEach
    void clean() {
        // The ':' in the sub-resource verb must reach the server literally. RestAssured percent-encodes
        // it by default, and '%3Aenumerate' matches no route — the same reason SimulationResourceTest
        // turns encoding off for ':simulate'. It is a global static, so it is restored after each test.
        RestAssured.urlEncodingEnabled = false;
        deleteAll();
    }

    @AfterEach
    void cleanup() {
        RestAssured.urlEncodingEnabled = true;
        deleteAll();
    }

    private void deleteAll() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
    }

    // ── Subject provenance is ADR-013 §5, reused ─────────────────────────────

    @Test
    @TestSecurity(user = CALLER)
    void enumeratingAnotherSubjectWithoutTheDelegationMarkerIsForbidden() {
        String app = "push-no-marker";
        seed(app);

        given().contentType(ContentType.JSON)
                .body("{\"subject\": \"" + OTHER_SUBJECT + "\"}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = CALLER, roles = DELEGATION_ROLE)
    void withTheMarkerTheEnumerationIsForTheRequestedSubject() {
        String app = "push-delegated";
        seed(app);

        given().contentType(ContentType.JSON)
                .body("{\"subject\": \"" + OTHER_SUBJECT + "\", \"subjectAttributes\": {\"area\": \"north\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .body("subject", equalTo(OTHER_SUBJECT))
                .body("app", equalTo(app));
    }

    /** No subject is self, and self needs no marker. */
    @Test
    @TestSecurity(user = CALLER)
    void anAbsentSubjectEnumeratesTheCaller() {
        String app = "push-self-absent";
        seed(app);

        given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"area\": \"north\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .body("subject", equalTo(CALLER));
    }

    /** Naming yourself is still self: the rule keys on difference, not on presence. */
    @Test
    @TestSecurity(user = CALLER)
    void anExplicitSubjectEqualToTheCallerNeedsNoMarker() {
        String app = "push-self-explicit";
        seed(app);

        given().contentType(ContentType.JSON)
                .body("{\"subject\": \"" + CALLER + "\", \"subjectAttributes\": {\"area\": \"north\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .body("subject", equalTo(CALLER));
    }

    // ── The load-bearing one: attributes are part of a result's identity ─────

    /**
     * THE TEST THIS CHANGE EXISTS FOR. One {@code (app, subject)}, two attribute bags: two different
     * bodies, two different ETags, and the first ETag must not revalidate the second.
     *
     * <p>Against the pre-change cache this fails twice over — the second call is served the first
     * call's cached body, and even past the TTL the shared ETag makes {@code If-None-Match} answer
     * {@code 304}, so the client keeps the wrong menu indefinitely. That second failure is why the
     * ETag had to change and not only the key.
     */
    @Test
    @TestSecurity(user = CALLER)
    void twoAttributeBagsForOneSubjectDifferInBodyAndInEtagAndDoNotRevalidate() {
        String app = "push-two-bags";
        seed(app);

        // north resolves the rule to a deterministic PERMIT: the pair is listed, unconditional.
        var north = given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"area\": \"north\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .body("permissions", hasSize(1))
                .body("permissions[0].action", equalTo("share"))
                .body("permissions[0].conditional", equalTo(false))
                .extract();

        // south resolves it to a deterministic DENY: the pair is omitted entirely.
        var south = given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"area\": \"south\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .body("permissions", hasSize(0))
                .extract();

        assertNotEquals(
                north.header("ETag"),
                south.header("ETag"),
                "two different menus for one subject must not carry the same validator");

        // And the half that outlives the TTL: the first validator must not revalidate the second.
        given().contentType(ContentType.JSON)
                .header("If-None-Match", north.header("ETag"))
                .body("{\"subjectAttributes\": {\"area\": \"south\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .body("permissions", hasSize(0));
    }

    /** Its own validator, however, still revalidates: the caching contract is unchanged. */
    @Test
    @TestSecurity(user = CALLER)
    void theSameBagRevalidatesWithItsOwnEtag() {
        String app = "push-revalidate";
        seed(app);

        String etag = given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"area\": \"north\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-None-Match", etag)
                .body("{\"subjectAttributes\": {\"area\": \"north\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(304);
    }

    // ── Degradation: fewer attributes, more conditional, never fewer pairs ───

    @Test
    @TestSecurity(user = CALLER)
    void anEmptyBagYieldsMoreConditionalPairsAndNeverFails() {
        String app = "push-empty-bag";
        seed(app);

        given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .body("permissions", hasSize(1))
                .body("permissions.action", contains("share"))
                .body("permissions[0].conditional", equalTo(true));
    }

    /** An absent bag is the same absence as an empty one, and equally not an error. */
    @Test
    @TestSecurity(user = CALLER)
    void anAbsentBagBehavesLikeAnEmptyOne() {
        String app = "push-absent-bag";
        seed(app);

        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .body("permissions[0].conditional", equalTo(true));
    }

    // ── The scope is the path's ──────────────────────────────────────────────

    @Test
    @TestSecurity(user = CALLER)
    void aBodyCarryingAppIsRejected() {
        String app = "push-app-in-body";
        seed(app);

        given().contentType(ContentType.JSON)
                .body("{\"app\": \"" + app + "\", \"subjectAttributes\": {\"area\": \"north\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    void unauthenticatedReturns401() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", "push-auth")
                .then()
                .statusCode(401);
    }

    /** The response shape is the GET's, not a second view type. */
    @Test
    @TestSecurity(user = CALLER)
    void theResponseCarriesTheSameShapeAndCachingHeadersAsTheGet() {
        String app = "push-shape";
        seed(app);

        given().contentType(ContentType.JSON)
                .body("{\"subjectAttributes\": {\"area\": \"north\"}}")
                .when()
                .post("/v1/apps/{app}/permissions:enumerate", app)
                .then()
                .statusCode(200)
                .header("ETag", not(equalTo(null)))
                // Same matcher the GET's own test uses: the directives are asserted, not their order,
                // which JAX-RS chooses.
                .header("Cache-Control", allOf(containsString("private"), containsString("max-age=30")))
                .body("app", equalTo(app))
                .body("subject", equalTo(CALLER))
                .body("permissions[0].resourceType", equalTo("document"))
                .body("generatedAt", not(equalTo(null)));
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /** One pair whose only rule reads {@code subject.attr.area}: permit, deny or unresolved. */
    private void seed(String app) {
        ActionCatalogueTestSupport.declare(catalogueRepository, app, "document", "share");

        Policy policy = new Policy(
                "p-share",
                1,
                "document",
                List.of("share"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule("r-permit", Effect.PERMIT, areaIs("north"))));

        lifecycleStore.create(app, policy, "seed", null);
        lifecycleStore.activate(app, policy.id(), 1, 0L, "seed", null);
    }

    private static Condition areaIs(String area) {
        return new Comparison(Operator.EQ, new AttributeRef("subject.attr.area"), new Literal(area));
    }
}
