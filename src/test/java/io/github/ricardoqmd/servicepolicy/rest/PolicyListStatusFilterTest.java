package io.github.ricardoqmd.servicepolicy.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * HTTP tests for ADR-025: {@code GET /v1/policies} lists every lifecycle state by default and
 * filters with {@code ?status=active|inactive|all}, composing with the {@code ?app=} filter of
 * ADR-024.
 *
 * <p>Policies are created and activated through the API, not seeded, so the listing is exercised
 * against exactly the state an administrator produces.
 */
@QuarkusTest
@TestSecurity(
        user = "admin-user",
        roles = {PolicyListStatusFilterTest.ADMIN})
class PolicyListStatusFilterTest {

    static final String ADMIN = "authz-admin";

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
    }

    /**
     * The bug ADR-025 closes: a policy is inactive the moment it is created (ADR-014/ADR-020), and
     * its creator must see it in the default listing — no query parameters, no prior activation.
     */
    @Test
    void createdPolicyIsVisibleInTheDefaultListingWhileStillInactive() {
        createPolicy("app-a", "freshly-created");

        given().when()
                .get("/v1/policies")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].policyId", equalTo("freshly-created"))
                .body("data[0].activeVersion", nullValue())
                .body("pagination.totalElements", equalTo(1));
    }

    @Test
    void statusActiveExcludesInactiveHeads() {
        createAndActivatePolicy("app-a", "p-active");
        createPolicy("app-a", "p-inactive");

        given().when()
                .get("/v1/policies?status=active")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].policyId", equalTo("p-active"))
                .body("data[0].activeVersion", equalTo(1))
                .body("pagination.totalElements", equalTo(1));
    }

    @Test
    void statusInactiveExcludesActiveHeads() {
        createAndActivatePolicy("app-a", "p-active");
        createPolicy("app-a", "p-inactive");

        given().when()
                .get("/v1/policies?status=inactive")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].policyId", equalTo("p-inactive"))
                .body("data[0].activeVersion", nullValue())
                .body("pagination.totalElements", equalTo(1));
    }

    @Test
    void explicitStatusAllIncludesBothStates() {
        createAndActivatePolicy("app-a", "p-active");
        createPolicy("app-a", "p-inactive");

        given().when()
                .get("/v1/policies?status=ALL") // parsing is case-insensitive
                .then()
                .statusCode(200)
                .body("data", hasSize(2))
                .body("data.policyId", contains("p-active", "p-inactive"))
                .body("pagination.totalElements", equalTo(2));
    }

    @Test
    void unknownStatusIsRejected() {
        given().when()
                .get("/v1/policies?status=basura")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("code", equalTo("BAD_REQUEST"));
    }

    /**
     * A present-but-empty '?status=' is an explicit invalid value, not an absent parameter: only
     * omitting the parameter altogether selects the 'all' default. Sending a blank one is a client
     * mistake and is rejected rather than silently defaulted.
     */
    @Test
    void blankStatusIsRejectedNotDefaulted() {
        given().when()
                .get("/v1/policies?status=")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("code", equalTo("BAD_REQUEST"));
    }

    /** ADR-025 §shape of the listing contract: the optional filters compose with AND. */
    @Test
    void statusAndAppFiltersComposeWithAnd() {
        createAndActivatePolicy("app-a", "a-active");
        createPolicy("app-a", "a-inactive");
        createPolicy("app-b", "b-inactive");

        given().when()
                .get("/v1/policies?app=app-a&status=inactive")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].policyId", equalTo("a-inactive"))
                .body("pagination.totalElements", equalTo(1));
    }

    /** Totals count what the filter selects, not the whole collection. */
    @Test
    void paginationTotalsFollowTheAppliedFilter() {
        createAndActivatePolicy("app-a", "p-1-active");
        createPolicy("app-a", "p-2-inactive");
        createPolicy("app-a", "p-3-inactive");

        given().when()
                .get("/v1/policies?size=2")
                .then()
                .statusCode(200)
                .body("data", hasSize(2))
                .body("pagination.totalElements", equalTo(3))
                .body("pagination.totalPages", equalTo(2));

        given().when()
                .get("/v1/policies?status=inactive&size=2")
                .then()
                .statusCode(200)
                .body("data", hasSize(2))
                .body("data.policyId", contains("p-2-inactive", "p-3-inactive"))
                .body("pagination.totalElements", equalTo(2))
                .body("pagination.totalPages", equalTo(1));
    }

    // --- API-driven setup ----------------------------------------------------

    private void createPolicy(String app, String policyId) {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "app": "%s",
                          "policyId": "%s",
                          "version": 1,
                          "resourceType": "document",
                          "actions": ["read"],
                          "combiningAlgorithm": "DENY_OVERRIDES",
                          "defaultEffect": "DENY",
                          "rules": []
                        }
                        """.formatted(app, policyId))
                .when()
                .post("/v1/policies")
                .then()
                .statusCode(201);
    }

    private void createAndActivatePolicy(String app, String policyId) {
        createPolicy(app, policyId);

        String etag = given().when()
                .get("/v1/policies/" + policyId)
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .body("{\"version\": 1}")
                .when()
                .post("/v1/policies/" + policyId + "/activate")
                .then()
                .statusCode(200);
    }
}
