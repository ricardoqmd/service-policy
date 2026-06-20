package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Smoke test: verifies the application starts and basic endpoints respond.
 *
 * <p>If this test passes, it means:
 * <ul>
 *   <li>The Quarkus application boots correctly.</li>
 *   <li>Configuration loads from application.yml.</li>
 *   <li>REST endpoints are reachable.</li>
 *   <li>Health checks return UP.</li>
 *   <li>OpenAPI is generated.</li>
 * </ul>
 *
 * <p>This is the first line of defense: if this breaks, nothing else will work.
 */
@QuarkusTest
class ApplicationSmokeTest {

    @Test
    void infoEndpointReturnsServiceMetadata() {
        given().when()
                .get("/info")
                .then()
                .statusCode(200)
                .body("name", equalTo("Service Policy"))
                .body("repository", equalTo("https://github.com/ricardoqmd/service-policy"))
                .body("startedAt", notNullValue())
                .body("uptime", notNullValue());
    }

    @Test
    void livenessCheckReportsUp() {
        given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void readinessCheckReportsUp() {
        given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void prometheusMetricsAreExposed() {
        given().when().get("/q/metrics").then().statusCode(200);
    }

    @Test
    void openApiSpecIsGenerated() {
        given().when().get("/q/openapi").then().statusCode(200);
    }
}
