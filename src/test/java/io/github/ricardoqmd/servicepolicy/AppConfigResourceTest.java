package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDocument;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for the per-application configuration surface (ADR-029):
 * {@code /v1/apps/{app}/configuration}. Covers the singleton CRUD contract, the ETag / If-Match
 * protocol (ADR-018) including precondition precedence, the admin gate (ADR-013), every validation
 * rule, and per-app isolation (ADR-026).
 */
@QuarkusTest
class AppConfigResourceTest {

    private static final String ADMIN = "authz-admin";

    private static final String APP = "test-app";

    private static final String CONFIG = "/v1/apps/{app}/configuration";

    private static final String FULL_CONFIG = """
            {
              "subjectAttributes": {"rol": "resource_access.test-app.roles"},
              "pip": {
                "url": "https://backend/api/subjects/{sub}/attributes",
                "timeoutMs": 500,
                "cacheTtlSeconds": 300,
                "credentialRef": "test-app-pip"
              }
            }
            """;

    @Inject
    AppConfigRepository configRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        configRepository.deleteAll();
    }

    // ── POST — create ────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createReturns201WithBareViewAndEtag() {
        given().contentType(ContentType.JSON)
                .body(FULL_CONFIG)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(201)
                .header("ETag", equalTo("\"1\""))
                .body("app", equalTo(APP))
                .body("revision", equalTo(1))
                .body("subjectAttributes.rol", equalTo("resource_access.test-app.roles"))
                .body("pip.url", equalTo("https://backend/api/subjects/{sub}/attributes"))
                .body("pip.timeoutMs", equalTo(500))
                .body("pip.cacheTtlSeconds", equalTo(300))
                .body("pip.credentialRef", equalTo("test-app-pip"));
    }

    /**
     * Sections are independently optional, and an absent one is <em>omitted</em> — the key is not in
     * the JSON at all, rather than present with a null. Asserted as key absence on purpose: a JSON
     * null and a missing key both read as {@code null} through a path expression, so
     * {@code nullValue()} would pass either way and could not tell the two apart.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithOnlySubjectAttributesOmitsThePipKeyEntirely() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"subjectAttributes": {"rol": "realm_access.roles"}}
                        """)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(201)
                .body("subjectAttributes.rol", equalTo("realm_access.roles"))
                .body("$", not(hasKey("pip")));

        given().when()
                .get(CONFIG, APP)
                .then()
                .statusCode(200)
                .body("$", not(hasKey("pip")))
                .body("$", hasKey("subjectAttributes"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithOnlyPipOmitsTheSubjectAttributesKeyEntirely() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"pip": {"url": "https://b/s/{sub}", "timeoutMs": 500,
                                 "cacheTtlSeconds": 300, "credentialRef": "ref"}}
                        """)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(201)
                .body("pip.url", equalTo("https://b/s/{sub}"))
                .body("$", not(hasKey("subjectAttributes")));

        given().when()
                .get(CONFIG, APP)
                .then()
                .statusCode(200)
                .body("$", not(hasKey("subjectAttributes")))
                .body("$", hasKey("pip"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWhenOneAlreadyExistsReturns409() {
        create();

        given().contentType(ContentType.JSON)
                .body(FULL_CONFIG)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(409)
                .body("code", equalTo("APP_CONFIG_ALREADY_EXISTS"))
                .body("title", equalTo("Application configuration already exists"))
                .body("detail", containsString("use PUT"));
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getReturnsTheConfigurationAndEtag() {
        create();

        given().when()
                .get(CONFIG, APP)
                .then()
                .statusCode(200)
                .header("ETag", equalTo("\"1\""))
                .body("app", equalTo(APP))
                .body("revision", equalTo(1))
                .body("pip.credentialRef", equalTo("test-app-pip"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void getWhenAbsentReturns404() {
        given().when()
                .get(CONFIG, APP)
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("code", equalTo("APP_CONFIG_NOT_FOUND"));
    }

    // ── PUT — replace ────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceReplacesTheWholeDocumentAndBumpsTheRevision() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"subjectAttributes": {"dept": "department"}}
                        """)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(200)
                .header("ETag", equalTo("\"2\""))
                .body("subjectAttributes.dept", equalTo("department"))
                .body("revision", equalTo(2))
                // Replace, not merge: the pip section the previous document had is gone — the key
                // itself, not merely its value.
                .body("$", not(hasKey("pip")));

        given().when()
                .get(CONFIG, APP)
                .then()
                .statusCode(200)
                .body("$", not(hasKey("pip")))
                .body("revision", equalTo(2));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithoutIfMatchReturns428() {
        create();

        given().contentType(ContentType.JSON)
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithUnparseableIfMatchReturns428() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"not-a-number\"")
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    /**
     * A whitespace-only If-Match is refused like an absent one — it carries no ETag to match.
     *
     * <p>It reaches {@code parseIfMatch} as {@code null} rather than as blank text: the HTTP layer
     * drops a header with no value before the resource sees it. The {@code isBlank()} arm of the
     * shared idiom is therefore unreachable over the wire and stays uncovered; it is kept because the
     * idiom is shared with the policy and catalogue resources, where the same is true. What this test
     * pins is the behaviour a client observes, which is the part that matters.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithBlankIfMatchReturns428() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "   ")
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    /**
     * The shared {@code parseIfMatch} idiom strips surrounding quotes when both are there and
     * otherwise takes the value as-is, so a client that sends the bare revision is accepted rather
     * than being failed over punctuation. Pinned here because it is a contract, not an accident.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithAnUnquotedIfMatchIsAccepted() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "1")
                .body("""
                        {"subjectAttributes": {"dept": "department"}}
                        """)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(200)
                .header("ETag", equalTo("\"2\""));
    }

    /**
     * Quotes are stripped only as a matched pair, so a half-quoted value is not silently repaired: it
     * falls through to the numeric parse, fails, and is refused. A truncated header is a client that
     * does not know what it is asserting, and guessing on its behalf is how a conditional write stops
     * being conditional.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithAHalfQuotedIfMatchReturns428() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1")
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteWithAnUnquotedIfMatchIsAccepted() {
        create();

        given().header("If-Match", "1").when().delete(CONFIG, APP).then().statusCode(204);
    }

    /** The 412 shape for a path-addressed resource: currentRevision, and no policyId. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithStaleIfMatchReturns412WithCurrentRevision() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"999\"")
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("currentRevision", equalTo(1))
                .body("policyId", nullValue());
    }

    /**
     * Precondition precedence: both objections apply, and the stale ETag is the one answered. A
     * client reasoning about a document it has not seen needs "reload and look again", not a
     * validation report on a body it may well not want to send once it has.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void staleIfMatchWithAnInvalidBodyReturns412NotValidationError() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"999\"")
                .body("""
                        {"pip": {"url": "not-a-url", "timeoutMs": 99999,
                                 "cacheTtlSeconds": -1, "credentialRef": ""}}
                        """)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWhenAbsentReturns404() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(404)
                .body("code", equalTo("APP_CONFIG_NOT_FOUND"));
    }

    /** The lost-update guard: a second write with the already-spent ETag loses (ADR-018). */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void doubleReplaceWithTheSameIfMatchSecondGets412() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(200);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"));
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteReturns204AndTheConfigurationIsGone() {
        create();

        given().header("If-Match", "\"1\"").when().delete(CONFIG, APP).then().statusCode(204);

        given().when().get(CONFIG, APP).then().statusCode(404);
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteWithoutIfMatchReturns428() {
        create();

        given().when().delete(CONFIG, APP).then().statusCode(428).body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteWithUnparseableIfMatchReturns428() {
        create();

        given().header("If-Match", "\"abc\"")
                .when()
                .delete(CONFIG, APP)
                .then()
                .statusCode(428)
                .body("code", equalTo("PRECONDITION_REQUIRED"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteWithStaleIfMatchReturns412() {
        create();

        given().header("If-Match", "\"999\"")
                .when()
                .delete(CONFIG, APP)
                .then()
                .statusCode(412)
                .body("code", equalTo("PRECONDITION_FAILED"))
                .body("currentRevision", equalTo(1));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void deleteWhenAbsentReturns404() {
        given().header("If-Match", "\"1\"")
                .when()
                .delete(CONFIG, APP)
                .then()
                .statusCode(404)
                .body("code", equalTo("APP_CONFIG_NOT_FOUND"));
    }

    // ── Admin gate ───────────────────────────────────────────────────────────

    @Test
    void unauthenticatedRequestReturns401() {
        given().when().get(CONFIG, APP).then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "plain-user")
    void getWithoutAdminMarkerReturns403() {
        given().when().get(CONFIG, APP).then().statusCode(403).body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void createWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .body(FULL_CONFIG)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void replaceWithoutAdminMarkerReturns403() {
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body(FULL_CONFIG)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "plain-user")
    void deleteWithoutAdminMarkerReturns403() {
        given().header("If-Match", "\"1\"")
                .when()
                .delete(CONFIG, APP)
                .then()
                .statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithNullBodyReturns400BadRequest() {
        given().contentType(ContentType.JSON)
                .body("null")
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** A body that is present but configures nothing is an invalid configuration, not a bad request. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithNeitherSectionReturns400InvalidAppConfig() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("title", equalTo("Invalid application configuration"))
                .body("invalidParams", hasSize(1))
                .body("invalidParams[0].field", equalTo("configuration"))
                .body("invalidParams[0].reason", containsString("at least one"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithEmptySubjectAttributesReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"subjectAttributes": {}}
                        """)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams[0].field", equalTo("subjectAttributes"))
                .body("invalidParams[0].reason", containsString("must not be empty"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithBlankAttributeNameReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"subjectAttributes": {"  ": "realm_access.roles"}}
                        """)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams[0].field", equalTo("subjectAttributes"))
                .body("invalidParams[0].reason", containsString("attribute names"));
    }

    /** The dotted path names the offending mapping, not just its section. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithBlankClaimPathReturns400NamingTheAttribute() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"subjectAttributes": {"rol": "   "}}
                        """)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams[0].field", equalTo("subjectAttributes.rol"))
                .body("invalidParams[0].reason", containsString("claim path"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithPipUrlLackingTheSubPlaceholderReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"https://backend/api/subjects/attributes\"", "500", "300", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams", hasSize(1))
                .body("invalidParams[0].field", equalTo("pip.url"))
                .body("invalidParams[0].reason", containsString("{sub}"));
    }

    /** Carries the placeholder but is not http/https: the scheme is checked independently. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithNonHttpPipUrlReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"ftp://backend/subjects/{sub}\"", "500", "300", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams", hasSize(1))
                .body("invalidParams[0].field", equalTo("pip.url"))
                .body("invalidParams[0].reason", containsString("http or https"));
    }

    /**
     * Parses cleanly and carries an http scheme, but is opaque rather than absolute — no host to
     * call. "http:backend/{sub}" is the shape a missing pair of slashes produces, so this is a typo
     * the validator has to catch rather than a contrived string.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithAHostlessPipUrlReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"http:backend/subjects/{sub}\"", "500", "300", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams", hasSize(1))
                .body("invalidParams[0].field", equalTo("pip.url"))
                .body("invalidParams[0].reason", containsString("http or https"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithMalformedPipUrlReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"ht tp://back end/{sub}\"", "500", "300", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams[0].field", equalTo("pip.url"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithBlankPipUrlReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"\"", "500", "300", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("invalidParams[0].field", equalTo("pip.url"))
                .body("invalidParams[0].reason", containsString("must not be blank"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithTimeoutBelowRangeReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"https://b/{sub}\"", "0", "300", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("invalidParams[0].field", equalTo("pip.timeoutMs"))
                .body("invalidParams[0].reason", containsString("between 1 and 10000"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithTimeoutAboveRangeReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"https://b/{sub}\"", "10001", "300", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("invalidParams[0].field", equalTo("pip.timeoutMs"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithCacheTtlBelowRangeReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"https://b/{sub}\"", "500", "-1", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("invalidParams[0].field", equalTo("pip.cacheTtlSeconds"))
                .body("invalidParams[0].reason", containsString("between 0 and 86400"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithCacheTtlAboveRangeReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"https://b/{sub}\"", "500", "86401", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("invalidParams[0].field", equalTo("pip.cacheTtlSeconds"));
    }

    /** Zero is in range for the TTL — "do not cache" is a legitimate choice, unlike a zero timeout. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithZeroCacheTtlIsAccepted() {
        given().contentType(ContentType.JSON)
                .body(pip("\"https://b/{sub}\"", "500", "0", "\"ref\""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(201)
                .body("pip.cacheTtlSeconds", equalTo(0));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithBlankCredentialRefReturns400() {
        given().contentType(ContentType.JSON)
                .body(pip("\"https://b/{sub}\"", "500", "300", "\"  \""))
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("invalidParams[0].field", equalTo("pip.credentialRef"));
    }

    /** Every pip field is required once the section is declared; each absence is its own violation. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithAnEmptyPipSectionReportsEveryMissingField() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"pip": {}}
                        """)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                .body("invalidParams", hasSize(4))
                .body(
                        "invalidParams.field",
                        containsInAnyOrder("pip.url", "pip.timeoutMs", "pip.cacheTtlSeconds", "pip.credentialRef"));
    }

    /** One request, several mistakes, one response listing all of them across both sections. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void multipleViolationsAreAllReportedInOneResponse() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "subjectAttributes": {"rol": ""},
                          "pip": {"url": "ftp://backend/subjects", "timeoutMs": 99999,
                                  "cacheTtlSeconds": 999999, "credentialRef": ""}
                        }
                        """)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"))
                // subjectAttributes.rol + url missing {sub} + url scheme + timeout + ttl + credentialRef
                .body("invalidParams", hasSize(6))
                .body(
                        "invalidParams.field",
                        containsInAnyOrder(
                                "subjectAttributes.rol",
                                "pip.url",
                                "pip.url",
                                "pip.timeoutMs",
                                "pip.cacheTtlSeconds",
                                "pip.credentialRef"));
    }

    /** ADR-026: the app is the path's; a body that also states it is rejected, not reconciled. */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void createWithAppInBodyReturns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"app": "test-app", "subjectAttributes": {"rol": "realm_access.roles"}}
                        """)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"))
                .body("detail", equalTo("'app' must not be present in the body; it is determined by the path."));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithAnInvalidBodyReturns400() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("{}")
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_APP_CONFIG"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void replaceWithNullBodyReturns400BadRequest() {
        create();

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("null")
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    // ── Write-time purity (ADR-029: no outbound calls on the write path) ─────

    /**
     * The configured URL points at a closed local port. Both writes must succeed — and succeed
     * immediately — because validation is syntax and bounds only. A source that is unreachable when
     * configuration is written is not a configuration error, and an admin write that dials out is an
     * admin write that can hang.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void writesDoNotContactTheConfiguredSource() {
        String unreachable = pip("\"http://127.0.0.1:1/subjects/{sub}\"", "500", "300", "\"ref\"");

        long startedAt = System.nanoTime();
        given().contentType(ContentType.JSON)
                .body(unreachable)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body(unreachable)
                .when()
                .put(CONFIG, APP)
                .then()
                .statusCode(200);

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        org.junit.jupiter.api.Assertions.assertTrue(
                elapsedMs < 2_000, "create+replace took " + elapsedMs + "ms; the write path must not dial out");
    }

    // ── Out-of-band data ─────────────────────────────────────────────────────

    /**
     * A stored mapping whose claim path is null — which the write path cannot produce, so it only
     * exists if something wrote to Mongo directly. The read must survive it: the broken entry is
     * dropped and the rest of the document is returned. Dropping a mapping makes one fewer attribute
     * derivable, which under deny-overrides can never widen a decision; failing the read instead
     * would turn one corrupt key into a 500 for the whole configuration.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void aStoredNullClaimPathIsDroppedRatherThanFailingTheRead() {
        AppConfigDocument seeded = new AppConfigDocument();
        seeded.app = APP;
        seeded.subjectAttributes = new Document("rol", "realm_access.roles").append("broken", null);
        seeded.revision = 1L;
        seeded.audit = new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
        configRepository.persist(seeded);

        given().when()
                .get(CONFIG, APP)
                .then()
                .statusCode(200)
                .body("subjectAttributes.rol", equalTo("realm_access.roles"))
                .body("subjectAttributes", not(hasKey("broken")))
                .body("revision", equalTo(1));
    }

    // ── Per-app isolation ────────────────────────────────────────────────────

    /**
     * Configuration is keyed by app alone, so the unique index must not make one app's document
     * block another's, and neither app may see the other's.
     */
    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void configurationsOfTwoAppsAreIndependent() {
        create();

        given().contentType(ContentType.JSON)
                .body("""
                        {"subjectAttributes": {"area": "org.area"}}
                        """)
                .when()
                .post(CONFIG, "other-app")
                .then()
                .statusCode(201)
                .body("app", equalTo("other-app"))
                .body("subjectAttributes.area", equalTo("org.area"));

        // Bumping one leaves the other's revision and content untouched.
        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("""
                        {"subjectAttributes": {"area": "org.unit"}}
                        """)
                .when()
                .put(CONFIG, "other-app")
                .then()
                .statusCode(200)
                .body("revision", equalTo(2));

        given().when()
                .get(CONFIG, APP)
                .then()
                .statusCode(200)
                .body("revision", equalTo(1))
                .body("subjectAttributes.rol", equalTo("resource_access.test-app.roles"));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {ADMIN})
    void theConfigurationOfAnotherAppIsInvisible() {
        create();

        given().when()
                .get(CONFIG, "other-app")
                .then()
                .statusCode(404)
                .body("code", equalTo("APP_CONFIG_NOT_FOUND"))
                .body("detail", containsString("other-app"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void create() {
        given().contentType(ContentType.JSON)
                .body(FULL_CONFIG)
                .when()
                .post(CONFIG, APP)
                .then()
                .statusCode(201);
    }

    private static String pip(String url, String timeoutMs, String cacheTtlSeconds, String credentialRef) {
        return """
                {"pip": {"url": %s, "timeoutMs": %s, "cacheTtlSeconds": %s, "credentialRef": %s}}
                """.formatted(url, timeoutMs, cacheTtlSeconds, credentialRef);
    }
}
