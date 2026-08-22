package io.github.ricardoqmd.servicepolicy;

import static io.github.ricardoqmd.servicepolicy.BatchEvaluationCapTest.batchOf;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * The batch cap is READ FROM CONFIGURATION, not a constant (ADR-031 §2).
 *
 * <p>This is the test that a hard-coded {@code 100} could not pass. It runs the same endpoint under
 * a profile that sets {@code service-policy.evaluation.batch-max-size=2} and shows the boundary
 * moving with it: 2 is accepted, 3 is rejected, and the rejection message carries the CONFIGURED
 * maximum rather than the default. Without it, {@link BatchEvaluationCapTest} alone would stay green
 * even if the property were never consulted.
 */
@QuarkusTest
@TestProfile(BatchEvaluationConfiguredCapTest.SmallBatchCapProfile.class)
class BatchEvaluationConfiguredCapTest {

    public static class SmallBatchCapProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("service-policy.evaluation.batch-max-size", "2");
        }
    }

    @Test
    @TestSecurity(user = "test-user")
    void batchOfExactlyTheConfiguredCapIsAccepted() {
        given().contentType(ContentType.JSON)
                .body(batchOf(2))
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(200)
                .body("decisions", hasSize(2));
    }

    @Test
    @TestSecurity(user = "test-user")
    void batchOverTheConfiguredCapIsRejectedWithThatMaximumInTheDetail() {
        given().contentType(ContentType.JSON)
                .body(batchOf(3))
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("code", equalTo("BAD_REQUEST"))
                // The configured 2, not the default 100: this is the assertion that proves the
                // property is read.
                .body("detail", equalTo("'requests' must contain between 1 and 2 items."));
    }
}
