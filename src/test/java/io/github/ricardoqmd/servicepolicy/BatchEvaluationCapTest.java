package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Batch size cap on {@code POST /v1/apps/{app}/evaluate/batch} (ADR-031), at the default bound.
 *
 * <p>The cap exists so the engine bounds its own work: {@code /evaluate} is consumed east-west by
 * backends, not through a gateway, so no upstream component can be assumed to protect it. The
 * boundary is what makes the bound real — a test that only sends an obviously huge batch would pass
 * against an off-by-one cap.
 *
 * <p>Configurability is proven separately, in {@link BatchEvaluationConfiguredCapTest}: green here
 * plus green there is what shows the limit is read from configuration and not a constant.
 */
@QuarkusTest
class BatchEvaluationCapTest {

    private static final int DEFAULT_CAP = 100;

    @Test
    @TestSecurity(user = "test-user")
    void batchOfExactlyTheCapIsAccepted() {
        given().contentType(ContentType.JSON)
                .body(batchOf(DEFAULT_CAP))
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(200)
                .body("decisions", hasSize(DEFAULT_CAP));
    }

    @Test
    @TestSecurity(user = "test-user")
    void batchOverTheCapIsRejectedWholeWithTheInterpolatedMaximum() {
        given().contentType(ContentType.JSON)
                .body(batchOf(DEFAULT_CAP + 1))
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("code", equalTo("BATCH_TOO_LARGE"))
                .body("status", equalTo(400))
                .body("detail", equalTo("'requests' must contain between 1 and 100 items."))
                // The cap travels as a number, not only inside the sentence: a client that must
                // re-chunk reacts to this rather than parsing prose.
                .body("maxBatchSize", equalTo(DEFAULT_CAP));
    }

    @Test
    @TestSecurity(user = "test-user")
    void emptyRequestsIsRejected() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": []
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("code", equalTo("BAD_REQUEST"))
                .body("detail", equalTo("'requests' must not be empty."));
    }

    /** The other arm: the field is absent altogether, not present-and-empty. */
    @Test
    @TestSecurity(user = "test-user")
    void absentRequestsIsRejected() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("code", equalTo("BAD_REQUEST"))
                .body("detail", equalTo("'requests' must not be empty."));
    }

    /**
     * The point of giving this rejection its own code: it is the only one on this surface a caller
     * can recover from. An empty batch is a programming mistake — the same request never works. An
     * oversized one is correct against a deployment with a larger cap, so a client that can tell the
     * two apart re-chunks and succeeds instead of reporting a bug.
     *
     * <p>Asserted as a difference, because a shared code is exactly what makes them indistinguishable.
     */
    @Test
    @TestSecurity(user = "test-user")
    void theOversizedRejectionIsDistinguishableFromEveryOtherBadRequest() {
        String oversized = given().contentType(ContentType.JSON)
                .body(batchOf(DEFAULT_CAP + 1))
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(400)
                .extract()
                .path("code");
        String empty = given().contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": []
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(400)
                .extract()
                .path("code");

        assertNotEquals(empty, oversized);
        assertEquals("BATCH_TOO_LARGE", oversized);
    }

    /** No other rejection on this surface carries the cap; it is not a field that leaked everywhere. */
    @Test
    @TestSecurity(user = "test-user")
    void anUnrelatedRejectionDoesNotCarryTheCap() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "requests": []
                        }
                        """)
                .when()
                .post("/v1/apps/test-app/evaluate/batch")
                .then()
                .statusCode(400)
                .body("$", not(hasKey("maxBatchSize")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A syntactically valid batch of {@code size} items. No policies are seeded, so every item falls
     * through to the fail-safe default deny — the decisions are uninteresting on purpose. What is
     * being measured here is how many items the endpoint accepts, not what it decides.
     */
    static String batchOf(int size) {
        // Built by concatenation rather than a text block inside the lambda: the formatter collapses
        // the latter into one unreadable line.
        String item = "{\"action\": \"document:read\", \"resource\": {\"type\": \"document\", \"id\": \"d%d\"}}";
        String items = IntStream.range(0, size).mapToObj(item::formatted).collect(Collectors.joining(","));
        return "{\"requests\":[" + items + "]}";
    }
}
