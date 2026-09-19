package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoCollection;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueStore;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigProvider;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigRepository;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * The schema marker of ADR-034 seen from the API: it never reaches a response or the OpenAPI
 * specification, and a stored document whose marker this build does not know fails the request.
 *
 * <p><strong>Absence is asserted by exact shape.</strong> Each response's field set is compared with the
 * set the contract names, so a marker — or any other field — that starts reaching the wire turns the
 * test red. Two responses are never compared with each other: both come from the same mapper, so a
 * leaked field would appear in both and the comparison would stay green.
 *
 * <p><strong>The unknown-marker failure is asserted on its status only.</strong> It is an unmapped
 * exception, and the body of the framework's default {@code 500} depends on the profile — in tests it
 * carries the exception class and the stack trace — so the body is not a contract and is not asserted.
 */
@QuarkusTest
class StoredDocumentSchemaResourceTest {

    @Inject
    ControlPlaneTestSupport controlPlane;

    private static final String APP = "schema-api";
    private static final String POLICIES = "/v1/apps/" + APP + "/policies";
    private static final int UNKNOWN = 999;

    private static final Set<String> HEAD_VIEW =
            Set.of("policyId", "app", "resourceType", "activeVersion", "revision", "audit", "activeContent");
    private static final Set<String> HEAD_SUMMARY =
            Set.of("policyId", "app", "resourceType", "activeVersion", "revision", "audit");
    private static final Set<String> VERSION_SUMMARY = Set.of("policyId", "app", "version", "resourceType", "audit");
    private static final Set<String> CONTENT =
            Set.of("policyId", "version", "resourceType", "actions", "combiningAlgorithm", "defaultEffect", "rules");
    private static final Set<String> PAGE = Set.of("data", "pagination");

    @Inject
    PolicyLifecycleStore policyStore;

    @Inject
    ActionCatalogueStore catalogueStore;

    @Inject
    AppConfigStore configStore;

    @Inject
    AppConfigProvider provider;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @Inject
    AppConfigRepository configRepository;

    private final PolicyDocumentMapper contentMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    @BeforeEach
    void clean() {
        wipe();
        controlPlane.installed();
    }

    @AfterEach
    void wipe() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
        configRepository.deleteAll();
        provider.invalidate(APP);
    }

    // ── The marker does not reach the wire ──────────────────────────────────────

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aPolicyHeadIsServedWithoutEitherMarker() {
        activePolicy("p-wire");

        JsonPath head = given().when()
                .get(POLICIES + "/p-wire")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertEquals(HEAD_VIEW, head.getMap("$").keySet());
        assertEquals(CONTENT, head.getMap("activeContent").keySet());
    }

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void thePolicyListsAreServedWithoutEitherMarker() {
        activePolicy("p-wire");

        JsonPath lean =
                given().when().get(POLICIES).then().statusCode(200).extract().jsonPath();
        JsonPath full = given().queryParam("view", "full")
                .when()
                .get(POLICIES)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertEquals(PAGE, lean.getMap("$").keySet());
        assertEquals(HEAD_SUMMARY, lean.getMap("data[0]").keySet());
        assertEquals(PAGE, full.getMap("$").keySet());
        assertEquals(HEAD_VIEW, full.getMap("data[0]").keySet());
        assertEquals(CONTENT, full.getMap("data[0].activeContent").keySet());
    }

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aPolicyVersionIsServedWithoutTheMarker() {
        activePolicy("p-wire");

        JsonPath one = given().when()
                .get(POLICIES + "/p-wire/versions/1")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        JsonPath lean = given().when()
                .get(POLICIES + "/p-wire/versions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        JsonPath full = given().queryParam("view", "full")
                .when()
                .get(POLICIES + "/p-wire/versions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertEquals(CONTENT, one.getMap("$").keySet());
        assertEquals(PAGE, lean.getMap("$").keySet());
        assertEquals(VERSION_SUMMARY, lean.getMap("data[0]").keySet());
        assertEquals(PAGE, full.getMap("$").keySet());
        assertEquals(CONTENT, full.getMap("data[0]").keySet());
    }

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aConfigurationIsServedWithoutTheMarker() {
        configStore.create(
                APP,
                new AppConfigDraft(
                        Map.of("rol", "realm_access.roles"),
                        new AppConfigDraft.PipDraft("https://backend/subjects/{sub}", 500, 300, "cred-ref")),
                AuditActor.verified("tester"));

        JsonPath config = given().when()
                .get("/v1/apps/" + APP + "/configuration")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();

        assertEquals(
                Set.of("app", "subjectAttributes", "pip", "revision"),
                config.getMap("$").keySet());
        assertEquals(
                Set.of("url", "timeoutMs", "cacheTtlSeconds", "credentialRef"),
                config.getMap("pip").keySet());
    }

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aCatalogueEntryIsServedWithoutTheMarker() {
        catalogueStore.create(APP, "invoice", List.of("read", "approve"), AuditActor.verified("tester"));
        String catalogue = "/v1/apps/" + APP + "/action-catalogue";

        JsonPath one = given().when()
                .get(catalogue + "/invoice")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        JsonPath all =
                given().when().get(catalogue).then().statusCode(200).extract().jsonPath();

        Set<String> entry = Set.of("app", "resourceType", "actions", "revision");
        assertEquals(entry, one.getMap("$").keySet());
        assertEquals(Set.of("data"), all.getMap("$").keySet());
        assertEquals(entry, all.getMap("data[0]").keySet());
    }

    /** Neither field name appears anywhere in the generated specification, in either format. */
    @Test
    void theOpenApiSpecificationNamesNeitherMarker() {
        for (String format : List.of("yaml", "json")) {
            given().queryParam("format", format)
                    .when()
                    .get("/q/openapi")
                    .then()
                    .statusCode(200)
                    .body(not(containsString("schemaVersion")))
                    .body(not(containsString("activeContentSchemaVersion")));
        }
    }

    // ── An unknown marker fails the request: status only ─────────────────────────

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aHeadWithAnUnknownMarkerFailsTheRead() {
        collection("policy_heads").insertOne(head("p-x", 1, policy("p-x", 1)).append("schemaVersion", UNKNOWN));

        given().when().get(POLICIES + "/p-x").then().statusCode(500);
    }

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aVersionWithAnUnknownMarkerFailsTheRead() {
        collection("policy_heads").insertOne(head("p-x", null, null));
        collection("policy_versions")
                .insertOne(new Document("app", APP)
                        .append("policyId", "p-x")
                        .append("version", 1)
                        .append("content", new Document(contentMapper.toDocument(policy("p-x", 1))))
                        .append("audit", audit())
                        .append("schemaVersion", UNKNOWN));

        given().when().get(POLICIES + "/p-x/versions/1").then().statusCode(500);
    }

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aConfigurationWithAnUnknownMarkerFailsTheRead() {
        collection("app_configs")
                .insertOne(new Document("app", APP)
                        .append("subjectAttributes", new Document("rol", "realm_access.roles"))
                        .append("revision", 1L)
                        .append("audit", audit())
                        .append("schemaVersion", UNKNOWN));

        given().when().get("/v1/apps/" + APP + "/configuration").then().statusCode(500);
    }

    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aCatalogueEntryWithAnUnknownMarkerFailsTheRead() {
        collection("action_catalogue")
                .insertOne(new Document("app", APP)
                        .append("resourceType", "invoice")
                        .append("actions", List.of("read"))
                        .append("revision", 1L)
                        .append("audit", audit())
                        .append("schemaVersion", UNKNOWN));

        given().when()
                .get("/v1/apps/" + APP + "/action-catalogue/invoice")
                .then()
                .statusCode(500);
    }

    /**
     * A build does not write to a head whose shape it does not know (ADR-034 §8). This append used to be
     * answered {@code 200} and written. It is now refused, and — asserted on the stored head, because the
     * response was never the problem — nothing is written, neither by it nor by a retry with the same
     * {@code If-Match}.
     */
    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void anAppendToAHeadOfAnUnknownShapeIsRefusedAndWritesNothing() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        collection("policy_heads").insertOne(head("p-x", null, null).append("schemaVersion", UNKNOWN));

        for (int attempt = 1; attempt <= 2; attempt++) {
            given().contentType(ContentType.JSON)
                    .header("If-Match", "\"1\"")
                    .body("{\"content\": %s}".formatted(policyJson("p-x", 1)))
                    .when()
                    .put(POLICIES + "/p-x")
                    .then()
                    .statusCode(500);

            Document stored =
                    collection("policy_heads").find(new Document("app", APP)).first();
            assertEquals(1L, stored.get("revision"), "attempt " + attempt);
            assertEquals(UNKNOWN, stored.get("schemaVersion"), "attempt " + attempt);
            assertEquals(
                    0, collection("policy_versions").countDocuments(new Document("app", APP)), "attempt " + attempt);
        }
    }

    /**
     * A head readable in its own shape, holding content copied in a shape this build does not know — as
     * another build's activation would leave it. The content is not served: {@code GET} fails. But a stale
     * {@code If-Match} is answered {@code 412} with the current revision, because reporting a revision
     * interprets no content, and with that revision the activation that replaces the content is reachable.
     */
    @Test
    @TestSecurity(user = "admin-user")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneTestSupport.TEST_APPS, type = ClaimType.JSON_ARRAY))
    void aClientCanLearnTheRevisionOfAHeadWhoseContentItCannotReadAndActivateOverIt() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        collection("policy_heads")
                .insertOne(head("p-x", 1, policy("p-x", 1))
                        .append("revision", 5L)
                        .append("schemaVersion", 1)
                        .append("activeContentSchemaVersion", UNKNOWN));
        collection("policy_versions").insertOne(version("p-x", 1));
        collection("policy_versions").insertOne(version("p-x", 2));

        given().when().get(POLICIES + "/p-x").then().statusCode(500);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"3\"")
                .body("{\"content\": %s}".formatted(policyJson("p-x", 3)))
                .when()
                .put(POLICIES + "/p-x")
                .then()
                .statusCode(412)
                .body("currentRevision", equalTo(5));
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"3\"")
                .body("{\"version\": 2}")
                .when()
                .post(POLICIES + "/p-x/activate")
                .then()
                .statusCode(412)
                .body("currentRevision", equalTo(5));
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"3\"")
                .when()
                .post(POLICIES + "/p-x/deactivate")
                .then()
                .statusCode(412)
                .body("currentRevision", equalTo(5));

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"5\"")
                .body("{\"version\": 2}")
                .when()
                .post(POLICIES + "/p-x/activate")
                .then()
                .statusCode(200)
                .body("activeVersion", equalTo(2))
                .body("revision", equalTo(6));
        given().when().get(POLICIES + "/p-x").then().statusCode(200);
    }

    /** An engine that guesses at the shape of a rule is worse than one that stops (ADR-034 §5). */
    @Test
    @TestSecurity(user = "test-user")
    void evaluationStopsOnAnActiveHeadWithAnUnknownMarker() {
        collection("policy_heads").insertOne(head("p-x", 1, policy("p-x", 1)).append("schemaVersion", UNKNOWN));

        given().contentType(ContentType.JSON)
                .body("""
                        {"action": "read", "resource": {"type": "document", "id": "d1"}}
                        """)
                .when()
                .post("/v1/apps/" + APP + "/evaluate")
                .then()
                .statusCode(500);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /** Created and activated through the store — the write path the API uses — so it carries markers. */
    private void activePolicy(String policyId) {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        policyStore.create(APP, policy(policyId, 1), AuditActor.verified("tester"), "first");
        policyStore.activate(APP, policyId, 1, 0L, AuditActor.verified("tester"), "live");
    }

    private MongoCollection<Document> collection(String name) {
        return headRepository.mongoDatabase().getCollection(name);
    }

    private Document head(String policyId, Integer activeVersion, Policy activeContent) {
        return new Document("policyId", policyId)
                .append("app", APP)
                .append("resourceType", "document")
                .append("activeVersion", activeVersion)
                .append(
                        "activeContent",
                        activeContent == null ? null : new Document(contentMapper.toDocument(activeContent)))
                .append("revision", 1L)
                .append("audit", audit());
    }

    /** A version as stored before the marker existed, so it reads as shape 1. */
    private Document version(String policyId, int version) {
        return new Document("app", APP)
                .append("policyId", policyId)
                .append("version", version)
                .append("content", new Document(contentMapper.toDocument(policy(policyId, version))))
                .append("audit", audit());
    }

    private static Document audit() {
        return new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
    }

    /** The same policy as {@link #policy}, as a request body. */
    private static String policyJson(String policyId, int version) {
        return """
                {
                  "policyId": "%s", "version": %d, "resourceType": "document",
                  "actions": ["read"],
                  "combiningAlgorithm": "DENY_OVERRIDES", "defaultEffect": "DENY",
                  "rules": [
                    {"id": "assigned-access", "effect": "PERMIT",
                     "condition": {"type": "comparison", "op": "IN",
                       "left": {"ref": "subject.id"}, "right": {"ref": "resource.attr.assignees"}}}
                  ]
                }
                """.formatted(policyId, version);
    }

    private static Policy policy(String id, int version) {
        return new Policy(
                id,
                version,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "assigned-access",
                        Effect.PERMIT,
                        new Comparison(
                                Operator.IN,
                                new AttributeRef("subject.id"),
                                new AttributeRef("resource.attr.assignees")))));
    }
}
