package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneFixtures;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * An action whose prefix disagrees with the resource type is refused before anything is evaluated, and
 * one that agrees, or carries no prefix, is not (ADR-036).
 *
 * <p>The property is held on every surface that carries an action beside a resource type — single
 * evaluation, batch and simulation — and asserted on the wire: the refusals below are compared, status
 * line, headers and body, with literals written in this file, never with another response.
 *
 * <p>Every refusal has a positive control. The same request with an agreeing action is evaluated against a
 * seeded policy that permits it and answers {@code allowed: true}: a suite where every case is refused
 * cannot tell the check from a broken endpoint, and a default deny cannot tell evaluation from its absence.
 */
@QuarkusTest
class ActionMustAgreeWithResourceTypeTest {

    private static final String APP = "test-app";
    private static final String CALLER = "admin-user";

    /** {@code document:read} asked about a {@code payment}, outside a batch. */
    static final String MISMATCH_ON_THE_WIRE = """
            HTTP/1.1 400 Bad Request
            content-length: 335
            content-type: application/problem+json

            {"type":"https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#action-resource-type-mismatch",\
            "code":"ACTION_RESOURCE_TYPE_MISMATCH","title":"Action does not match resource type","status":400,\
            "detail":"action prefix 'document' does not match resource.type 'payment'.",\
            "actionPrefix":"document","resourceType":"payment"}""";

    /** The same disagreement as the second item of a batch whose other items agree. */
    static final String BATCH_MISMATCH_ON_THE_WIRE = """
            HTTP/1.1 400 Bad Request
            content-length: 358
            content-type: application/problem+json

            {"type":"https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#action-resource-type-mismatch",\
            "code":"ACTION_RESOURCE_TYPE_MISMATCH","title":"Action does not match resource type","status":400,\
            "detail":"requests[1]: action prefix 'document' does not match resource.type 'payment'.",\
            "actionPrefix":"document","resourceType":"payment","index":1}""";

    /** The same disagreement as the FIRST item of a batch. */
    static final String BATCH_MISMATCH_AT_FIRST_ON_THE_WIRE = """
            HTTP/1.1 400 Bad Request
            content-length: 358
            content-type: application/problem+json

            {"type":"https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#action-resource-type-mismatch",\
            "code":"ACTION_RESOURCE_TYPE_MISMATCH","title":"Action does not match resource type","status":400,\
            "detail":"requests[0]: action prefix 'document' does not match resource.type 'payment'.",\
            "actionPrefix":"document","resourceType":"payment","index":0}""";

    /** The same disagreement as the LAST of three items. */
    static final String BATCH_MISMATCH_AT_LAST_ON_THE_WIRE = """
            HTTP/1.1 400 Bad Request
            content-length: 358
            content-type: application/problem+json

            {"type":"https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#action-resource-type-mismatch",\
            "code":"ACTION_RESOURCE_TYPE_MISMATCH","title":"Action does not match resource type","status":400,\
            "detail":"requests[2]: action prefix 'document' does not match resource.type 'payment'.",\
            "actionPrefix":"document","resourceType":"payment","index":2}""";

    /** {@code document:} — a prefix and nothing after the colon — outside a batch. */
    static final String BLANK_VERB_ON_THE_WIRE = """
            HTTP/1.1 400 Bad Request
            content-length: 209
            content-type: application/problem+json

            {"type":"https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#bad-request",\
            "code":"BAD_REQUEST","title":"Bad request","status":400,\
            "detail":"the verb of action 'document:' must not be blank."}""";

    /** The same blank verb as the second item of a batch. */
    static final String BATCH_BLANK_VERB_ON_THE_WIRE = """
            HTTP/1.1 400 Bad Request
            content-length: 222
            content-type: application/problem+json

            {"type":"https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#bad-request",\
            "code":"BAD_REQUEST","title":"Bad request","status":400,\
            "detail":"requests[1]: the verb of action 'document:' must not be blank."}""";

    /** The surfaces of ADR-036: every request that carries an action beside a resource type. */
    enum Surface {
        EVALUATE {
            @Override
            Response send(String action, String resourceType) {
                return post("/v1/apps/" + APP + "/evaluate", request(action, resourceType));
            }
        },
        BATCH {
            /** The case under test is the second of three items; the other two always agree. */
            @Override
            Response send(String action, String resourceType) {
                return post(
                        "/v1/apps/" + APP + "/evaluate/batch",
                        "{\"requests\":[" + request("document:read", "document") + "," + request(action, resourceType)
                                + "," + request("read", "document") + "]}");
            }
        },
        SIMULATE {
            /** The candidate governs the request's own type, so an agreeing request is permitted by it. */
            @Override
            Response send(String action, String resourceType) {
                return post(
                        "/v1/apps/" + APP + "/policies:simulate",
                        "{\"policy\":" + candidate(resourceType) + ",\"request\":" + request(action, resourceType)
                                + "}");
            }
        };

        abstract Response send(String action, String resourceType);

        /** @return whether every decision in a 200 answer permits: one for a single call, three for a batch. */
        static boolean allPermitted(Response response) {
            if (response.jsonPath().get("decisions") == null) {
                return Boolean.TRUE.equals(response.jsonPath().getBoolean("allowed"));
            }
            List<Boolean> allowed = response.jsonPath().getList("decisions.allowed", Boolean.class);
            return allowed.size() == 3 && allowed.stream().allMatch(Boolean.TRUE::equals);
        }
    }

    @Inject
    ControlPlaneTestSupport controlPlane;

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @Inject
    StoreReads storeReads;

    @BeforeEach
    void arrange() {
        RestAssured.urlEncodingEnabled = false;
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
        // "a" with the verb "b:c" is the shape whose prefix and verb only the FIRST colon separates.
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "payment", "read");
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "a", "b:c");
        activate(openTo("document", "read"));
        activate(openTo("payment", "read"));
        activate(openTo("a", "b:c"));
        controlPlane.installed();
        storeReads.clear();
    }

    @AfterEach
    void cleanUp() {
        RestAssured.urlEncodingEnabled = true;
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
    }

    // ── the disagreement, refused ────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void anActionForAnotherTypeIsRefusedWithItsOwnCode(Surface surface) {
        String expected = surface == Surface.BATCH ? BATCH_MISMATCH_ON_THE_WIRE : MISMATCH_ON_THE_WIRE;
        assertEquals(expected, ControlPlaneFixtures.wire(surface.send("document:read", "payment")));
    }

    /** The positive control of the refusal above: the same request, agreeing, is evaluated and permitted. */
    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void theSameRequestWithAnAgreeingActionIsEvaluated(Surface surface) {
        Response response = surface.send("payment:read", "payment");
        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(Surface.allPermitted(response), response.asString());
    }

    /** Equality is literal: a spelling a human reads as the same type is another type here (ADR-036 §3). */
    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aPrefixThatDiffersOnlyInCaseIsRefused(Surface surface) {
        Response response = surface.send("Payment:read", "payment");
        assertEquals(400, response.statusCode(), response.asString());
        assertEquals("ACTION_RESOURCE_TYPE_MISMATCH", response.jsonPath().getString("code"));
        assertEquals("Payment", response.jsonPath().getString("actionPrefix"));
        assertEquals("payment", response.jsonPath().getString("resourceType"));
    }

    /** A colon with nothing before it is an empty prefix, which names no type the request carries. */
    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void anEmptyPrefixIsRefused(Surface surface) {
        Response response = surface.send(":read", "payment");
        assertEquals(400, response.statusCode(), response.asString());
        assertEquals("ACTION_RESOURCE_TYPE_MISMATCH", response.jsonPath().getString("code"));
        assertEquals("", response.jsonPath().getString("actionPrefix"));
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aBlankVerbIsRefused(Surface surface) {
        String expected = surface == Surface.BATCH ? BATCH_BLANK_VERB_ON_THE_WIRE : BLANK_VERB_ON_THE_WIRE;
        assertEquals(expected, ControlPlaneFixtures.wire(surface.send("document:", "document")));
    }

    /**
     * Whitespace is a character like any other (ADR-036 §3): a prefix that differs from the type only by a
     * leading or a trailing space names another type, and nothing is trimmed before the comparison.
     */
    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aPrefixWithALeadingSpaceIsRefused(Surface surface) {
        assertMismatch(surface, surface.send(" payment:read", "payment"), " payment", "payment");
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aPrefixWithATrailingSpaceIsRefused(Surface surface) {
        assertMismatch(surface, surface.send("payment :read", "payment"), "payment ", "payment");
    }

    /** The other side of the comparison is not trimmed either: {@code resource.type} is taken as sent. */
    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aResourceTypeWithSurroundingSpacesIsComparedAsSent(Surface surface) {
        assertMismatch(surface, surface.send("payment:read", " payment "), "payment", " payment ");
    }

    // ── a batch is checked at every position ─────────────────────────────────

    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aDisagreeingFirstItemRefusesTheBatch() {
        Response response = batch(
                request("document:read", "payment"), request("payment:read", "payment"), request("read", "document"));
        assertEquals(BATCH_MISMATCH_AT_FIRST_ON_THE_WIRE, ControlPlaneFixtures.wire(response));
    }

    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aDisagreeingLastItemRefusesTheBatch() {
        Response response = batch(
                request("payment:read", "payment"), request("read", "document"), request("document:read", "payment"));
        assertEquals(BATCH_MISMATCH_AT_LAST_ON_THE_WIRE, ControlPlaneFixtures.wire(response));
    }

    /**
     * Of two disagreeing items, the refusal names the one with the lowest index. The two disagree
     * differently, so the body tells them apart by more than the index.
     */
    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void ofTwoDisagreeingItemsTheFirstIsNamed() {
        Response response = batch(
                request("document:read", "payment"), request("read", "document"), request("payment:read", "document"));
        assertEquals(BATCH_MISMATCH_AT_FIRST_ON_THE_WIRE, ControlPlaneFixtures.wire(response));
    }

    // ── the batch items this rule does not check ─────────────────────────────

    /**
     * An item without a {@code resource.type} has no pair to compare, so this rule does not refuse it: it
     * is evaluated and answered with the per-item deny it always had, and the items around it are decided.
     */
    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aBatchItemWithoutAResourceTypeIsAnsweredWithADeny() {
        assertDeniedForItsMissingType(batch(
                request("payment:read", "payment"),
                "{\"action\":\"document:read\",\"resource\":{\"id\":\"r1\"}}",
                request("read", "document")));
    }

    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aBatchItemWithABlankResourceTypeIsAnsweredWithADeny() {
        assertDeniedForItsMissingType(
                batch(request("payment:read", "payment"), request("document:read", ""), request("read", "document")));
    }

    /**
     * A {@code null} item is skipped by this rule, which still checks the items after it. The same item in a
     * batch that nothing refuses is answered 500 today: that failure predates ADR-036 and is not this
     * rule's to fix — the assertion records it so that fixing it is a visible change.
     */
    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aNullBatchItemIsNotCheckedAndItsSuccessorsAre() {
        assertEquals(
                BATCH_MISMATCH_ON_THE_WIRE,
                ControlPlaneFixtures.wire(batch("null", request("document:read", "payment"))));

        assertEquals(500, batch(request("payment:read", "payment"), "null").statusCode());
    }

    /** The same for an item without an action, which is likewise answered 500 today when nothing refuses. */
    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aBatchItemWithoutAnActionIsNotCheckedAndItsSuccessorsAre() {
        String withoutAction = "{\"resource\":{\"type\":\"payment\",\"id\":\"r1\"}}";
        assertEquals(
                BATCH_MISMATCH_ON_THE_WIRE,
                ControlPlaneFixtures.wire(batch(withoutAction, request("document:read", "payment"))));

        assertEquals(
                500, batch(request("payment:read", "payment"), withoutAction).statusCode());
    }

    // ── the three shapes that stay valid ─────────────────────────────────────

    /** No prefix, no disagreement (ADR-036 §2): a bare verb names no type, so it cannot contradict one. */
    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aBareVerbIsEvaluated(Surface surface) {
        Response response = surface.send("read", "payment");
        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(Surface.allPermitted(response), response.asString());
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aPrefixEqualToTheTypeIsEvaluated(Surface surface) {
        Response response = surface.send("document:read", "document");
        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(Surface.allPermitted(response), response.asString());
    }

    /**
     * The split is at the FIRST colon: {@code a:b:c} is prefix {@code a} and verb {@code b:c}, which agrees
     * with a resource of type {@code a} and is permitted by the policy that governs {@code b:c} on it. Split
     * at the last colon, the prefix would be {@code a:b} and the request refused.
     */
    @ParameterizedTest
    @EnumSource(Surface.class)
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aVerbThatItselfContainsAColonIsEvaluated(Surface surface) {
        Response response = surface.send("a:b:c", "a");
        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(Surface.allPermitted(response), response.asString());
    }

    // ── before anything is read ──────────────────────────────────────────────

    /**
     * One disagreeing item refuses the whole batch before any item is evaluated: not one read scoped to the
     * application reaches the store. The positive control is the same batch agreeing, which reads it —
     * without it, an empty record could mean the listener sees nothing.
     */
    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aRefusedBatchReadsNoPolicy() {
        assertEquals(400, Surface.BATCH.send("document:read", "payment").statusCode());
        assertEquals(List.of(), storeReads.scopedTo(APP));

        assertEquals(200, Surface.BATCH.send("payment:read", "payment").statusCode());
        assertFalse(storeReads.scopedTo(APP).isEmpty());
    }

    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aRefusedEvaluationReadsNoPolicy() {
        assertEquals(400, Surface.EVALUATE.send("document:read", "payment").statusCode());
        assertEquals(List.of(), storeReads.scopedTo(APP));

        assertEquals(200, Surface.EVALUATE.send("payment:read", "payment").statusCode());
        assertFalse(storeReads.scopedTo(APP).isEmpty());
    }

    /**
     * On simulation the refusal precedes the candidate's validation and its resolution against the
     * application's catalogue, so it reads nothing of the application either. What it does read is the
     * control-plane authorization of the caller, which is scoped to the reserved application and precedes
     * every validation of this body (ADR-033).
     */
    @Test
    @TestSecurity(user = CALLER)
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aRefusedSimulationReadsNothingOfTheApplication() {
        assertEquals(400, Surface.SIMULATE.send("document:read", "payment").statusCode());
        assertEquals(List.of(), storeReads.scopedTo(APP));

        assertEquals(200, Surface.SIMULATE.send("payment:read", "payment").statusCode());
        assertFalse(storeReads.scopedTo(APP).isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Response post(String path, String body) {
        return given().contentType(ContentType.JSON).body(body).when().post(path);
    }

    private static Response batch(String... items) {
        return post("/v1/apps/" + APP + "/evaluate/batch", "{\"requests\":[" + String.join(",", items) + "]}");
    }

    /** A refusal with the new code naming both compared values, and on a batch the index of its second item. */
    private static void assertMismatch(Surface surface, Response response, String actionPrefix, String resourceType) {
        assertEquals(400, response.statusCode(), response.asString());
        assertEquals("ACTION_RESOURCE_TYPE_MISMATCH", response.jsonPath().getString("code"));
        assertEquals(actionPrefix, response.jsonPath().getString("actionPrefix"));
        assertEquals(resourceType, response.jsonPath().getString("resourceType"));
        assertEquals(
                surface == Surface.BATCH ? Integer.valueOf(1) : null,
                response.jsonPath().get("index"));
    }

    /** Three decisions: the middle item denied for its missing type, the two around it evaluated and permitted. */
    private static void assertDeniedForItsMissingType(Response response) {
        assertEquals(200, response.statusCode(), response.asString());
        assertEquals(List.of(true, false, true), response.jsonPath().getList("decisions.allowed", Boolean.class));
        assertEquals("resource.type must not be blank", response.jsonPath().getString("decisions[1].reason"));
    }

    private static String request(String action, String resourceType) {
        return "{\"action\":\"" + action + "\",\"resource\":{\"type\":\"" + resourceType + "\",\"id\":\"r1\"}}";
    }

    /** A candidate for simulation that permits the caller everything catalogued on {@code resourceType}. */
    private static String candidate(String resourceType) {
        return """
                {"policyId": "draft", "version": 1, "resourceType": "%s", "actions": ["*"],
                 "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                 "rules": [{"id": "open-to-caller", "effect": "PERMIT",
                   "condition": {"type": "comparison", "op": "EQ",
                     "left": {"ref": "subject.id"}, "right": {"value": "%s"}}}]}
                """.formatted(resourceType, CALLER);
    }

    /** A policy that permits the caller {@code verb} on {@code resourceType}, and nothing else. */
    private static Policy openTo(String resourceType, String verb) {
        Rule openToCaller = new Rule(
                "open-to-caller",
                Effect.PERMIT,
                new Comparison(Operator.EQ, new AttributeRef("subject.id"), new Literal(CALLER)));
        return new Policy(
                "open-" + resourceType,
                1,
                resourceType,
                List.of(verb),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(openToCaller));
    }

    private void activate(Policy policy) {
        AuditActor seed = AuditActor.verified("seed-subject");
        lifecycleStore.create(APP, policy, seed, "seed");
        lifecycleStore.activate(APP, policy.id(), 1, 0L, seed, "seed");
    }
}
