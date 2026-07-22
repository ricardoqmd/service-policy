package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadDocument;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/**
 * Integration tests for authoring against the action catalogue (ADR-028): {@code '*'} is expanded at
 * create/append and never stored, actions outside the catalogue are rejected, the simulator
 * validates identically (ADR-027), and an action an ACTIVE policy still names cannot be removed from
 * the catalogue.
 *
 * <p>The expansion assertions read the stored version back, not the create response: the property
 * that matters is what was <em>persisted</em>, since {@code /evaluate} reads storage and must never
 * meet a {@code '*'}.
 *
 * <p>URL encoding is off so the {@code :simulate} colon travels raw, as in
 * {@code SimulationResourceTest}.
 */
@QuarkusTest
@TestSecurity(
        user = "admin-user",
        roles = {PolicyActionCatalogueTest.ADMIN})
class PolicyActionCatalogueTest {

    static final String ADMIN = "authz-admin";

    private static final String APP = "test-app";

    private static final String POLICIES = "/v1/apps/{app}/policies";

    private static final String SIMULATE = "/v1/apps/{app}/policies:simulate";

    private static final String CATALOGUE_ENTRY = "/v1/apps/{app}/action-catalogue/{resourceType}";

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @BeforeEach
    void clean() {
        RestAssured.urlEncodingEnabled = false;
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
    }

    @AfterEach
    void cleanup() {
        RestAssured.urlEncodingEnabled = true;
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
    }

    // ── Expansion at authoring ───────────────────────────────────────────────

    /**
     * The stored version carries the explicit catalogue list, in catalogue order, and no {@code '*'}
     * — the whole point of ADR-028: adding a verb later must not widen this policy.
     */
    @Test
    void createWithWildcardPersistsTheExplicitCatalogueList() {
        declare("document", "create", "read", "update", "delete");

        given().contentType(ContentType.JSON)
                .body(policy("wild", 1, "document", "\"*\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(201);

        given().when()
                .get(POLICIES + "/wild/versions/1", APP)
                .then()
                .statusCode(200)
                .body("actions", contains("create", "read", "update", "delete"))
                .body("actions", not(hasItem("*")));
    }

    @Test
    void appendWithWildcardPersistsTheExplicitCatalogueList() {
        declare("document", "read", "delete");

        given().contentType(ContentType.JSON)
                .body(policy("wild-append", 1, "document", "\"read\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(201);

        String etag = given().when()
                .get(POLICIES + "/wild-append", APP)
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("{\"content\": %s}".formatted(policy("wild-append", 2, "document", "\"*\"")))
                .when()
                .put(POLICIES + "/wild-append", APP)
                .then()
                .statusCode(200)
                .body("version", equalTo(2));

        given().when()
                .get(POLICIES + "/wild-append/versions/2", APP)
                .then()
                .statusCode(200)
                .body("actions", contains("read", "delete"))
                .body("actions", not(hasItem("*")));
    }

    // ── Rejections at authoring ──────────────────────────────────────────────

    /** {@code '*'} means "all of them"; mixing it with a named verb is a contradiction, not a union. */
    @Test
    void wildcardMixedWithAnExplicitActionReturns400() {
        declare("document", "read", "delete");

        given().contentType(ContentType.JSON)
                .body(policy("mixed", 1, "document", "\"*\", \"read\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams", hasSize(1))
                .body("invalidParams[0].field", equalTo("actions"))
                .body("invalidParams[0].reason", containsString("must be the only element"));
    }

    /** Every unknown action is reported, not just the first: the author fixes them in one pass. */
    @Test
    void unknownActionsAreEachReportedAsAnInvalidParam() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .body(policy("unknown", 1, "document", "\"read\", \"export\", \"shred\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams", hasSize(2))
                .body("invalidParams.field", contains("actions", "actions"))
                .body("invalidParams[0].reason", containsString("'export'"))
                .body("invalidParams[1].reason", containsString("'shred'"));
    }

    /** An undeclared resource type blocks authoring for it — declare the vocabulary first. */
    @Test
    void createWithNoCatalogueForTheResourceTypeReturns400() {
        given().contentType(ContentType.JSON)
                .body(policy("orphan", 1, "undeclared", "\"read\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].field", equalTo("actions"))
                .body("invalidParams[0].reason", containsString("declare the catalogue before authoring"));

        assertEquals(0, headRepository.count());
        assertEquals(0, versionRepository.count());
    }

    /** '*' against an app that declares nothing is rejected, not silently expanded to nothing. */
    @Test
    void createWithWildcardAndNoCatalogueReturns400() {
        given().contentType(ContentType.JSON)
                .body(policy("orphan-wild", 1, "undeclared", "\"*\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].reason", containsString("no action catalogue for resource type"));
    }

    @Test
    void appendWithAnUnknownActionReturns400() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .body(policy("append-unknown", 1, "document", "\"read\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(201);

        String etag = given().when()
                .get(POLICIES + "/append-unknown", APP)
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("{\"content\": %s}".formatted(policy("append-unknown", 2, "document", "\"purge\"")))
                .when()
                .put(POLICIES + "/append-unknown", APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].reason", containsString("'purge'"));

        // Version 2 was not appended.
        given().when().get(POLICIES + "/append-unknown/versions/2", APP).then().statusCode(404);
    }

    /**
     * Document validation precedes target resolution: appending an uncatalogued document to a policy
     * that does not exist returns 400, not 404. The resolver is the first statement of
     * {@code PolicyLifecycleStore.append}, which is what makes "no uncatalogued policy is ever
     * stored" a property of the store rather than of its callers — and the cost is that a caller who
     * gets both wrong at once hears about the body first. The catalogued-body case still yields the
     * 404 (see {@code PolicyWriteResourceTest#putOnNonExistentPolicyReturns404}).
     */
    @Test
    void appendToAnUnknownPolicyWithAnUndeclaredResourceTypeReturns400NotFound404() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body("{\"content\": %s}".formatted(policy("ghost", 1, "undeclared", "\"read\"")))
                .when()
                .put(POLICIES + "/ghost", APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].field", equalTo("actions"))
                .body("invalidParams[0].reason", containsString("declare the catalogue before authoring"));
    }

    /** The catalogue is per-app: an action declared in one app is unknown in another. */
    @Test
    void anActionCataloguedInAnotherAppIsStillUnknownHere() {
        ActionCatalogueTestSupport.declare(catalogueRepository, "other-app", "document", "read", "publish");
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .body(policy("cross-app", 1, "document", "\"publish\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].reason", containsString("'publish'"));
    }

    // ── Simulate validates exactly as create (ADR-027 + ADR-028) ─────────────

    /** The candidate's {@code ['*']} expands, so the simulated policy applies to the request's verb. */
    @Test
    void simulateExpandsWildcardAndEvaluates() {
        declare("document", "read", "delete");

        given().contentType(ContentType.JSON)
                .body(simulateBody(policy("draft", 1, "document", "\"*\""), permitRequest()))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule assigned-access"));

        // Zero effect: a simulation persists nothing, catalogue resolution included.
        assertEquals(0, headRepository.count());
        assertEquals(0, versionRepository.count());
    }

    @Test
    void simulateWithAnUncataloguedActionReturns400() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .body(simulateBody(policy("draft", 1, "document", "\"incinerate\""), permitRequest()))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].field", equalTo("actions"))
                .body("invalidParams[0].reason", containsString("'incinerate'"));

        assertEquals(0, headRepository.count());
        assertEquals(0, versionRepository.count());
    }

    @Test
    void simulateWithNoCatalogueForTheResourceTypeReturns400() {
        given().contentType(ContentType.JSON)
                .body(simulateBody(policy("draft", 1, "undeclared", "\"*\""), permitRequest()))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_POLICY"))
                .body("invalidParams[0].reason", containsString("declare the catalogue before authoring"));

        assertEquals(0, headRepository.count());
        assertEquals(0, versionRepository.count());
    }

    // --- Legacy wildcard data is inert at evaluation ---

    /**
     * A stored wildcard policy is inert: it is never selected, so with no other applicable
     * policy the request is denied for lack of candidates — not because the wildcard policy
     * denies anything. Do not read this single-policy case as the general contract: see
     * aStoredWildcardDenyDoesNotSuppressAnotherPolicysPermit for what inertness means when
     * other policies apply. No migration is needed because no pre-R028 data exists and the
     * write path can no longer produce a stored "*" (ADR-028).
     */
    @Test
    void aStoredWildcardPolicyGovernsNothingAndTheRequestIsDenied() {
        declare("document", "read", "delete");
        seedActiveHeadWithContent(legacyWildcardPolicy());

        given().contentType(ContentType.JSON)
                .body(permitRequest())
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("no applicable policy"));

        // The same for the app's other catalogued verb — the wildcard is inert, not verb-specific.
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:delete",
                          "resource": {"type": "document", "id": "d1", "attributes": {"assignees": ["admin-user"]}}
                        }
                        """)
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }

    /**
     * The other arm of selection, reached through simulate. On the active-head path the resource type
     * is already filtered in Mongo by {@code activePoliciesFor}, so a candidate whose type does not
     * match the request can only arise here, where the caller supplies the policy directly (ADR-027).
     * The catalogue check still passes — it is keyed by the CANDIDATE's resource type — and selection
     * is what rejects it, which is the separation of concerns the two guards are meant to have.
     */
    @Test
    void simulateWithACandidateOfAnotherResourceTypeSelectsNothing() {
        declare("document", "read");

        given().contentType(ContentType.JSON)
                .body(simulateBody(policy("draft", 1, "document", "\"read\""), """
                        {
                          "action": "folder:read",
                          "resource": {"type": "folder", "id": "f1", "attributes": {"assignees": ["admin-user"]}}
                        }
                        """))
                .when()
                .post(SIMULATE, APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("no applicable policy"));
    }

    /**
     * Inert is not the same as denied, and this is the case that proves it.
     *
     * <p>Two ACTIVE policies on the same {@code (app, resourceType)}: a legacy one carrying
     * {@code ["*"]} whose rule would DENY this exact request, and a post-ADR-028 one carrying the
     * literal verb whose rule PERMITs it. Under deny-overrides, if the wildcard policy were selected
     * the decision would be DENY. It is not selected — an unselected policy contributes nothing, not
     * its rules and not its {@code defaultEffect} — so the surviving candidate permits.
     *
     * <p>So removing the wildcard arm did not make legacy data fail closed; it made it invisible,
     * and an invisible DENY is a decision that can flip from deny to permit. That is accepted
     * (ADR-028 §Consequences): no pre-ADR-028 data exists, the write door can no longer produce a
     * stored {@code '*'}, and a database that has one was written out of band. This test exists to
     * keep that consequence documented and deliberate rather than discovered later.
     */
    @Test
    void aStoredWildcardDenyDoesNotSuppressAnotherPolicysPermit() {
        declare("document", "read", "delete");
        seedActiveHeadWithContent(new Policy(
                "legacy-wildcard-deny",
                1,
                "document",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule("legacy-deny", Effect.DENY, assigneeMatches()))));
        seedActiveHeadWithContent(new Policy(
                "literal-permit",
                1,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule("assigned-access", Effect.PERMIT, assigneeMatches()))));

        // allowed=true documents wildcard inertness under deny-overrides: the legacy DENY is not
        // among the candidates, so it cannot override the literal policy's permit.
        given().contentType(ContentType.JSON)
                .body(permitRequest())
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule assigned-access"));
    }

    /** The control: the same head with a literal action IS selected and permits. */
    @Test
    void aStoredLiteralActionPolicyIsSelectedAndPermits() {
        declare("document", "read", "delete");
        seedActiveHeadWithContent(new Policy(
                "legacy", 1, "document", List.of("read"), CombiningAlgorithm.DENY_OVERRIDES, Effect.PERMIT, List.of()));

        given().contentType(ContentType.JSON)
                .body(permitRequest())
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));
    }

    // ── ACTION_IN_USE: removal is guarded by active policies ─────────────────

    /**
     * The full cycle of the guard: an active policy naming {@code delete} blocks its removal from the
     * catalogue, and the same write succeeds once that policy is deactivated. Nothing about the
     * policy changes in between — only whether it is deciding anything.
     */
    @Test
    void removingAnActionUsedByAnActivePolicyIsRejectedUntilItIsDeactivated() {
        declare("document", "read", "delete");
        createAndActivate("shredder", "\"delete\"");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(409)
                .body("code", equalTo("ACTION_IN_USE"))
                .body("title", equalTo("Action in use"))
                .body("policyIds", contains("shredder"))
                .body("detail", containsString("delete"));

        // The catalogue is unchanged — a rejected write is not a partial one.
        given().when()
                .get(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read", "delete"))
                .body("revision", equalTo(1));

        deactivate("shredder");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read"))
                .body("revision", equalTo(2));
    }

    /**
     * The guard discriminates, it does not just detect that active policies exist. Here a genuine
     * removal happens ('archive' leaves the vocabulary) while an ACTIVE policy of the same
     * {@code (app, resourceType)} stands — but that policy governs 'read', not 'archive', so it is
     * examined and found irrelevant. Without this the suite could not tell a working guard from one
     * that blocks whenever any active policy is present.
     */
    @Test
    void anActivePolicyThatDoesNotUseTheRemovedActionDoesNotBlock() {
        declare("document", "read", "delete", "archive");
        createAndActivate("reader", "\"read\"");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read", "delete"]}
                        """)
                .when()
                .put(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read", "delete"))
                .body("revision", equalTo(2));
    }

    /** Keeping the action and merely adding another is never blocked: widening is safe by construction. */
    @Test
    void addingAnActionIsNeverBlockedByAnActivePolicy() {
        declare("document", "read", "delete");
        createAndActivate("shredder", "\"delete\"");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read", "delete", "archive"]}
                        """)
                .when()
                .put(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read", "delete", "archive"));
    }

    /** Deleting the entry removes every action at once, so an active policy blocks it too. */
    @Test
    void deletingAnEntryUsedByAnActivePolicyIsRejected() {
        declare("document", "read", "delete");
        createAndActivate("shredder", "\"delete\"");

        given().header("If-Match", "\"1\"")
                .when()
                .delete(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(409)
                .body("code", equalTo("ACTION_IN_USE"))
                .body("policyIds", contains("shredder"));

        given().when().get(CATALOGUE_ENTRY, APP, "document").then().statusCode(200);

        deactivate("shredder");

        given().header("If-Match", "\"1\"")
                .when()
                .delete(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(204);
    }

    /** An INACTIVE policy decides nothing, so it never blocks a catalogue change. */
    @Test
    void anInactivePolicyDoesNotBlockRemoval() {
        declare("document", "read", "delete");

        given().contentType(ContentType.JSON)
                .body(policy("never-activated", 1, "document", "\"delete\""))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read"));
    }

    /**
     * A stale {@code If-Match} wins over the in-use guard: 412, not 409. Both objections apply here —
     * the ETag is out of date AND the removal is blocked — and the precondition is the one that gets
     * answered (RFC 9110 §13.1). A client holding a stale ETag is reasoning about an entry it has not
     * seen; telling it "delete is in use" would invite it to act on that stale picture, while 412
     * says the only thing that is certainly true: reload and look again.
     */
    @Test
    void staleIfMatchOnAnInUseRemovalReturns412NotConflict() {
        declare("document", "read", "delete");
        createAndActivate("shredder", "\"delete\"");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"999\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("currentRevision", equalTo(1));
    }

    /** The same ordering on DELETE, where the guard covers every action of the entry. */
    @Test
    void staleIfMatchOnAnInUseDeleteReturns412NotConflict() {
        declare("document", "read", "delete");
        createAndActivate("shredder", "\"delete\"");

        given().header("If-Match", "\"999\"")
                .when()
                .delete(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("currentRevision", equalTo(1));

        // Neither objection wrote anything: the entry is intact.
        given().when()
                .get(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read", "delete"));
    }

    /** A policy of another app never blocks: the guard is scoped to (app, resourceType). */
    @Test
    void anActivePolicyOfAnotherAppDoesNotBlockRemoval() {
        declare("document", "read", "delete");
        ActionCatalogueTestSupport.declare(catalogueRepository, "other-app", "document", "read", "delete");

        given().contentType(ContentType.JSON)
                .body(policy("elsewhere", 1, "document", "\"delete\""))
                .when()
                .post(POLICIES, "other-app")
                .then()
                .statusCode(201);
        activate("other-app", "elsewhere");

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"actions": ["read"]}
                        """)
                .when()
                .put(CATALOGUE_ENTRY, APP, "document")
                .then()
                .statusCode(200)
                .body("actions", contains("read"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void declare(String resourceType, String... actions) {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, resourceType, actions);
    }

    /** Holds for {@link #permitRequest()}: the caller is in the resource's assignees. */
    private static Condition assigneeMatches() {
        return new Comparison(Operator.IN, new AttributeRef("subject.id"), new AttributeRef("resource.attr.assignees"));
    }

    /** A pre-ADR-028 policy: {@code ["*"]} where the vocabulary used to be resolved at match time. */
    private static Policy legacyWildcardPolicy() {
        return new Policy(
                "legacy", 1, "document", List.of("*"), CombiningAlgorithm.DENY_OVERRIDES, Effect.PERMIT, List.of());
    }

    /**
     * Persists an ACTIVE head whose {@code activeContent} is the given policy, straight through the
     * repository. This deliberately bypasses {@code PolicyLifecycleStore}, because the store is now
     * the thing that would refuse the document — which is the point: the only way this shape exists
     * is as data written before ADR-028, and that is what the evaluator has to be safe against.
     */
    private void seedActiveHeadWithContent(Policy content) {
        PolicyHeadDocument head = new PolicyHeadDocument();
        head.policyId = content.id();
        head.app = APP;
        head.resourceType = content.resourceType();
        head.activeVersion = content.version();
        head.activeContent = new Document(new PolicyDocumentMapper(new ConditionDocumentMapper()).toDocument(content));
        head.revision = 1L;
        head.audit = new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
        headRepository.persist(head);
    }

    private void createAndActivate(String policyId, String actionsJson) {
        given().contentType(ContentType.JSON)
                .body(policy(policyId, 1, "document", actionsJson))
                .when()
                .post(POLICIES, APP)
                .then()
                .statusCode(201);
        activate(APP, policyId);
    }

    private static void activate(String app, String policyId) {
        String etag = given().when()
                .get(POLICIES + "/" + policyId, app)
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("{\"version\": 1}")
                .when()
                .post(POLICIES + "/" + policyId + "/activate", app)
                .then()
                .statusCode(200);
    }

    private static void deactivate(String policyId) {
        String etag = given().when()
                .get(POLICIES + "/" + policyId, APP)
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .when()
                .post(POLICIES + "/" + policyId + "/deactivate", APP)
                .then()
                .statusCode(200);
    }

    /** A policy that PERMITs an assignee — the shape is irrelevant here; only 'actions' is. */
    private static String policy(String policyId, int version, String resourceType, String actionsJson) {
        return """
                {
                  "policyId": "%s", "version": %d, "resourceType": "%s",
                  "actions": [%s],
                  "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                  "rules": [
                    {"id": "assigned-access", "effect": "PERMIT",
                     "condition": {"type": "comparison", "op": "IN",
                       "left": {"ref": "subject.id"}, "right": {"ref": "resource.attr.assignees"}}}
                  ]
                }
                """.formatted(policyId, version, resourceType, actionsJson);
    }

    private static String permitRequest() {
        return """
                {
                  "action": "document:read",
                  "resource": {"type": "document", "id": "d1", "attributes": {"assignees": ["admin-user"]}}
                }
                """;
    }

    private static String simulateBody(String policyJson, String requestJson) {
        return """
                {
                  "policy": %s,
                  "request": %s
                }
                """.formatted(policyJson, requestJson);
    }
}
