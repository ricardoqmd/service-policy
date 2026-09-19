package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.ConditionDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyDocumentMapper;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;

/**
 * The merged catalogue {@code GET /v1/policies}, scoped to what the caller may read before the query is
 * issued (ADR-033 §2).
 *
 * <p>Seeded: {@code app-mine} has 3 heads ({@code p-1}, {@code p-3}, {@code p-5}), {@code app-mine-2} has 2
 * ({@code p-2}, {@code p-4}), {@code app-other} has 4, and the reserved application has its baseline policy. Totals and pages are asserted, not only rows, because
 * filtering a page after the query would return the right rows under an untrue total.
 */
@QuarkusTest
class MergedCatalogueScopeTest {

    private static final String CATALOGUE = "/v1/policies";

    /** The empty result, as a client receives it: identical whether the application is foreign or absent. */
    static final String EMPTY_PAGE_ON_THE_WIRE = """
            HTTP/1.1 200 OK
            content-encoding: gzip
            content-length: 91
            content-type: application/json;charset=UTF-8

            {"data":[],"pagination":{"page":1,"size":20,"totalPages":0,"totalElements":0}}""";

    @Inject
    ControlPlaneFixtures fixtures;

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    AppConfigStore configStore;

    private final PolicyDocumentMapper mapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    @BeforeEach
    void arrange() {
        fixtures.wipe();
        policies(ControlPlaneFixtures.MINE, 3);
        policies(ControlPlaneFixtures.MINE_2, 2);
        policies(ControlPlaneFixtures.OTHER, 4);
        fixtures.controlPlane.installed();
    }

    @AfterEach
    void cleanUp() {
        fixtures.wipe();
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = "[\"app-mine\"]", type = ClaimType.JSON_ARRAY))
    void aCallerWithOneApplicationSeesOnlyItsRowsAndItsTotal() {
        given().when()
                .get(CATALOGUE)
                .then()
                .statusCode(200)
                .body("data.app", contains("app-mine", "app-mine", "app-mine"))
                .body("pagination.totalElements", equalTo(3))
                .body("pagination.totalPages", equalTo(1));
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void aCallerWithTwoApplicationsSeesThemMergedWithTrueTotalsAndPages() {
        given().when()
                .get(CATALOGUE + "?size=2&page=3")
                .then()
                .statusCode(200)
                .body("data.policyId", contains("p-5"))
                .body("data.app", contains("app-mine"))
                .body("pagination.totalElements", equalTo(5))
                .body("pagination.totalPages", equalTo(3));
        given().when()
                .get(CATALOGUE + "?size=20")
                .then()
                .statusCode(200)
                .body("data.app", contains("app-mine", "app-mine-2", "app-mine", "app-mine-2", "app-mine"))
                .body("pagination.totalElements", equalTo(5));
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = "[]", type = ClaimType.JSON_ARRAY))
    void aCallerWithNoApplicationGetsAnEmptyPageAndNeverA403() {
        assertEquals(
                EMPTY_PAGE_ON_THE_WIRE, ControlPlaneFixtures.wire(given().when().get(CATALOGUE)));
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "sub", value = "console-a"))
    void aCallerWithoutTheClaimGetsTheSameEmptyPage() {
        assertEquals(
                EMPTY_PAGE_ON_THE_WIRE, ControlPlaneFixtures.wire(given().when().get(CATALOGUE)));
    }

    /** Both are compared with the literal, never with each other. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void anApplicationOutsideTheScopeAnswersExactlyAsOneThatDoesNotExist() {
        assertEquals(
                EMPTY_PAGE_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().when().get(CATALOGUE + "?app=" + ControlPlaneFixtures.OTHER)));
        assertEquals(
                EMPTY_PAGE_ON_THE_WIRE,
                ControlPlaneFixtures.wire(given().when().get(CATALOGUE + "?app=" + ControlPlaneFixtures.NONCE)));
    }

    /** The positive control of the filter: inside the scope, it narrows. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = ControlPlaneFixtures.MY_APPS, type = ClaimType.JSON_ARRAY))
    void anApplicationInsideTheScopeIsFiltered() {
        given().when()
                .get(CATALOGUE + "?app=" + ControlPlaneFixtures.MINE_2 + "&status=inactive")
                .then()
                .statusCode(200)
                .body("data.app", contains("app-mine-2", "app-mine-2"))
                .body("pagination.totalElements", equalTo(2));
    }

    /** The reserved application is a candidate like any other: it shows only to a caller that holds it. */
    @Test
    @TestSecurity(user = "operator")
    @OidcSecurity(
            claims =
                    @Claim(
                            key = "apps",
                            value = "[\"service-policy-control-plane\",\"app-other\"]",
                            type = ClaimType.JSON_ARRAY))
    void theReservedApplicationIsVisibleOnlyToACallerThatHoldsIt() {
        given().when()
                .get(CATALOGUE + "?status=active")
                .then()
                .statusCode(200)
                .body("data.app", contains("service-policy-control-plane"))
                .body("data.policyId", contains(ControlPlaneInstallation.BASELINE_POLICY_ID))
                .body("pagination.totalElements", equalTo(1));
    }

    /**
     * The scope of the merged view resolves {@code apps} through the mapping stored for the reserved
     * application, exactly as a per-application decision does — not through the deployment property
     * (ADR-033 §2, §3).
     *
     * <p>Arranged so that the two sources disagree, which is the only arrangement that can tell them apart:
     * the stored mapping names {@code other_apps}, the property of the test profile names {@code apps}, and
     * the token carries both with opposing contents. Resolving by the property would show rows of
     * {@code app-other} — the application whose per-application read is refused in the same case.
     */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "apps", value = "[\"app-other\"]", type = ClaimType.JSON_ARRAY),
                @Claim(key = "other_apps", value = "[\"app-mine\"]", type = ClaimType.JSON_ARRAY)
            })
    void theScopeComesFromTheStoredMappingAndNotFromTheDeploymentProperty() {
        long revision = configStore
                .find(ControlPlaneTestSupport.RESERVED_APP)
                .orElseThrow()
                .revision();
        configStore.replace(
                ControlPlaneTestSupport.RESERVED_APP,
                new AppConfigDraft(Map.of("apps", "other_apps"), null),
                revision,
                AuditActor.verified("test"));

        given().when()
                .get(CATALOGUE)
                .then()
                .statusCode(200)
                .body("data.app", contains("app-mine", "app-mine", "app-mine"))
                .body("pagination.totalElements", equalTo(3));

        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(403);
    }

    private void policies(String app, int count) {
        fixtures.seedCatalogue(app);
        for (int i = 1; i <= count; i++) {
            String policy = ControlPlaneFixtures.POLICY_TEMPLATE.formatted(
                    "p-" + (app.equals("app-mine-2") ? i * 2 : i * 2 - 1));
            lifecycleStore.create(app, mapper.fromDocument(Json.parse(policy)), AuditActor.verified("seed"), null);
        }
    }
}
