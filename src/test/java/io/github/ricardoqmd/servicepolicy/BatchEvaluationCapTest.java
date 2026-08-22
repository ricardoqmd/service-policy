package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

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
                .body("code", equalTo("BAD_REQUEST"))
                .body("status", equalTo(400))
                .body("detail", equalTo("'requests' must contain between 1 and 100 items."));
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
