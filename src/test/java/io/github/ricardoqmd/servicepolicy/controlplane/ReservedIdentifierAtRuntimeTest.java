package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.inject.Inject;

import org.bson.Document;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneFixtures.Endpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;

/**
 * The reserved identifier binds while the service runs, not only when it starts (ADR-033 §6).
 *
 * <p>The state is the one of a second deployment started during the installation window with a different
 * identifier: another instance's write closed installation and recorded its own identifier, and this one keeps
 * running. Every control-plane decision reads the marker already, so it compares the recorded identifier with
 * the configured one and, when they disagree, denies all eighteen endpoints and answers the merged catalogue
 * with its empty page — to a caller that would otherwise be permitted everything. The positive control is the
 * same caller, the same requests and a marker that agrees.
 */
@QuarkusTest
class ReservedIdentifierAtRuntimeTest {

    /** A caller that holds its own applications and the reserved one: permitted everything when bound. */
    private static final String HOLDER_APPS =
            "[\"app-mine\",\"app-mine-2\",\"" + ControlPlaneTestSupport.RESERVED_APP + "\"]";

    @Inject
    ControlPlaneFixtures fixtures;

    private final List<LogRecord> records = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void arrange() {
        fixtures.arrange();
        Logger.getLogger(ControlPlaneAuthorizer.class.getName()).addHandler(capture);
    }

    @AfterEach
    void cleanUp() {
        Logger.getLogger(ControlPlaneAuthorizer.class.getName()).removeHandler(capture);
        fixtures.wipe();
        fixtures.controlPlane.installed();
    }

    static List<Endpoint> endpoints() {
        return ControlPlaneFixtures.endpoints();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = HOLDER_APPS, type = ClaimType.JSON_ARRAY))
    void aMarkerRecordingAnotherIdentifierDeniesEveryEndpoint(Endpoint endpoint) {
        recordedIdentifier("cp-installed-elsewhere");

        String app = endpoint.needsFreshApp() ? ControlPlaneFixtures.MINE_2 : ControlPlaneFixtures.MINE;
        assertEquals(
                ControlPlaneRefusalOnTheWireTest.DENIED_ON_THE_WIRE,
                ControlPlaneFixtures.wire(endpoint.send(app)),
                endpoint.name());
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = HOLDER_APPS, type = ClaimType.JSON_ARRAY))
    void aMarkerRecordingAnotherIdentifierEmptiesTheMergedCatalogue() {
        recordedIdentifier("cp-installed-elsewhere");

        given().when().get("/v1/policies").then().statusCode(200).body("pagination.totalElements", equalTo(0));
    }

    /** A marker whose identifier this build cannot read is a disagreement too, never a licence to assume one. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = HOLDER_APPS, type = ClaimType.JSON_ARRAY))
    void aMarkerWithAnUnreadableIdentifierDeniesAsWell() {
        markerField(Updates.set("reservedApp", 5));

        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(403);
    }

    /** The positive control: the same caller and requests, a marker that agrees, and everything succeeds. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = HOLDER_APPS, type = ClaimType.JSON_ARRAY))
    void aMarkerRecordingThisIdentifierDecidesNormally(Endpoint endpoint) {
        String app = endpoint.needsFreshApp() ? ControlPlaneFixtures.MINE_2 : ControlPlaneFixtures.MINE;
        assertEquals(endpoint.success(), endpoint.send(app).statusCode(), endpoint.name());
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = HOLDER_APPS, type = ClaimType.JSON_ARRAY))
    void aMarkerRecordingThisIdentifierKeepsTheMergedCatalogue() {
        given().when().get("/v1/policies").then().statusCode(200).body("pagination.totalElements", equalTo(2));
        assertEquals(0, records.size(), "an agreeing marker logged a disagreement");
    }

    /** Logged when first seen, not per request; it names the property and both identifiers. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = HOLDER_APPS, type = ClaimType.JSON_ARRAY))
    void theDisagreementIsLoggedOnce() {
        String elsewhere = "cp-logged-once-" + System.nanoTime();
        recordedIdentifier(elsewhere);

        for (int i = 0; i < 3; i++) {
            given().when()
                    .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                    .then()
                    .statusCode(403);
            given().when().get("/v1/policies").then().statusCode(200);
        }

        List<String> disagreements = records.stream()
                .map(ReservedIdentifierAtRuntimeTest::message)
                .filter(message -> message.contains(elsewhere))
                .toList();
        assertEquals(1, disagreements.size(), "logged per request: " + disagreements);
        String logged = disagreements.get(0);
        assertTrue(logged.contains("service-policy.control-plane.reserved-app"), logged);
        assertTrue(logged.contains(ControlPlaneTestSupport.RESERVED_APP), logged);
    }

    /** Agreement clears what was reported: the same disagreement returning is a new transition, logged again. */
    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(claims = @Claim(key = "apps", value = HOLDER_APPS, type = ClaimType.JSON_ARRAY))
    void theSameDisagreementIsLoggedAgainWhenItReturnsAfterAgreement() {
        String elsewhere = "cp-logged-again-" + System.nanoTime();

        recordedIdentifier(elsewhere);
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(403);
        recordedIdentifier(ControlPlaneTestSupport.RESERVED_APP);
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
        recordedIdentifier(elsewhere);
        for (int i = 0; i < 3; i++) {
            given().when()
                    .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                    .then()
                    .statusCode(403);
        }

        List<String> disagreements = records.stream()
                .map(ReservedIdentifierAtRuntimeTest::message)
                .filter(message -> message.contains(elsewhere))
                .toList();
        assertEquals(2, disagreements.size(), "not logged on each transition into disagreement: " + disagreements);
    }

    private void recordedIdentifier(String reservedApp) {
        markerField(Updates.set("reservedApp", reservedApp));
    }

    private void markerField(org.bson.conversions.Bson update) {
        fixtures.controlPlane
                .installationRepository
                .mongoDatabase()
                .getCollection("installation", Document.class)
                .updateOne(Filters.eq("_id", "control-plane"), update);
    }

    private static String message(LogRecord record) {
        return record instanceof ExtLogRecord extended ? extended.getFormattedMessage() : record.getMessage();
    }
}
