package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.bson.Document;
import org.bson.RawBsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueStore;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigProvider;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.ForeignDocumentsQuery;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHead;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ClaimType;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;

/**
 * Seeding finishes what it started (ADR-033 §6, ADR-019): a start interrupted half-way leaves part of
 * installation's own documents, and the next start completes them instead of skipping them because a head
 * exists — then reads the end state back and refuses to start if it does not hold.
 *
 * <p>The interrupted states are produced the way a real failure produces them: by the seeding code itself, with
 * a collection validator that makes one of its writes fail, which aborts the start at that write. The validator
 * is then removed and the start repeated.
 */
@QuarkusTest
class InstallationConvergesOnItsEndStateTest {

    private static final String RESERVED = ControlPlaneTestSupport.RESERVED_APP;
    private static final String BASELINE = ControlPlaneInstallation.BASELINE_POLICY_ID;
    private static final String BOOTSTRAP = ControlPlaneTestSupport.BOOTSTRAP_SUBJECT;
    private static final List<String> COLLECTIONS =
            List.of("app_configs", "action_catalogue", "policy_heads", "policy_versions", "installation");

    /** A validator that refuses to activate: the start dies between create and activate. */
    private static final Document NO_ACTIVATION = new Document("activeVersion", null);

    /** A validator that refuses every version: the start dies between the head and its version 1. */
    private static final Document NO_VERSION = new Document("_id", new Document("$exists", false));

    @Inject
    ControlPlaneFixtures fixtures;

    @Inject
    InstallationStore installationStore;

    @Inject
    AppConfigStore configStore;

    @Inject
    AppConfigProvider configProvider;

    @Inject
    ActionCatalogueStore catalogueStore;

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    ForeignDocumentsQuery foreignDocuments;

    @BeforeEach
    void arrange() {
        fixtures.wipe();
        fixtures.seed(ControlPlaneFixtures.MINE);
        fixtures.controlPlane.installationRepository.deleteAll();
        configProvider.invalidate(RESERVED);
    }

    @AfterEach
    void cleanUp() {
        validator("policy_heads", new Document());
        validator("policy_versions", new Document());
        fixtures.wipe();
        fixtures.controlPlane.installed();
    }

    // ── both interrupted states ──────────────────────────────────────────────────

    @Test
    void aStartInterruptedBeforeActivationIsCompletedByTheNext() {
        interrupted("policy_heads", NO_ACTIVATION);
        PolicyHead left = lifecycleStore.findHead(RESERVED, BASELINE).orElseThrow();
        assertNull(left.activeVersion(), "the interruption did not happen where intended");
        assertEquals(1, lifecycleStore.countVersions(RESERVED, BASELINE));

        installation().prepare();

        assertEndState();
    }

    @Test
    void aStartInterruptedBeforeVersionOneIsCompletedByTheNext() {
        interrupted("policy_versions", NO_VERSION);
        assertTrue(lifecycleStore.headExists(RESERVED, BASELINE), "the interruption did not happen where intended");
        assertEquals(0, lifecycleStore.countVersions(RESERVED, BASELINE));

        installation().prepare();

        assertEndState();
    }

    /** README step 4 over a completed store: the bootstrap closes, and administration then works by claim. */
    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = BOOTSTRAP),
                @Claim(key = "apps", value = "[\"app-mine\",\"" + RESERVED + "\"]", type = ClaimType.JSON_ARRAY)
            })
    void afterCompletingTheDocumentedCloseLeavesAWorkingControlPlane() {
        interrupted("policy_heads", NO_ACTIVATION);
        installation().prepare();
        configProvider.invalidate(RESERVED);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("{\"subjectAttributes\": {\"apps\": \"apps\"}}")
                .when()
                .put("/v1/apps/{app}/configuration", RESERVED)
                .then()
                .statusCode(200);
        assertTrue(installationStore.marker().installed());

        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
        given().contentType(ContentType.JSON)
                .body("{\"resourceType\": \"after-close\", \"actions\": [\"read\"]}")
                .when()
                .post("/v1/apps/{app}/action-catalogue", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(201);
    }

    /** Skipping the half-written baseline would install this store with nothing active, denying everyone. */
    @Test
    @TestSecurity(user = BOOTSTRAP)
    @OidcSecurity(
            claims = {
                @Claim(key = "sub", value = BOOTSTRAP),
                @Claim(key = "apps", value = "[\"app-mine\",\"" + RESERVED + "\"]", type = ClaimType.JSON_ARRAY)
            })
    void afterCompletingAVersionlessHeadTheDocumentedCloseLeavesAWorkingControlPlane() {
        interrupted("policy_versions", NO_VERSION);
        installation().prepare();
        configProvider.invalidate(RESERVED);

        given().contentType(ContentType.JSON)
                .header("If-Match", "\"1\"")
                .body("{\"subjectAttributes\": {\"apps\": \"apps\"}}")
                .when()
                .put("/v1/apps/{app}/configuration", RESERVED)
                .then()
                .statusCode(200);

        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
    }

    // ── the end state that cannot be reached ─────────────────────────────────────

    /** A configuration of installation's own whose mapping is unusable is not completed: it is refused. */
    @Test
    void anEndStateThatDoesNotHoldRefusesToStart() {
        // installation's own configuration, its mapping made unusable afterwards: the store refuses to write one
        configStore.create(RESERVED, new AppConfigDraft(Map.of("apps", "apps"), null), AuditActor.installation());
        database()
                .getCollection("app_configs")
                .updateOne(Filters.eq("app", RESERVED), Updates.set("subjectAttributes.apps", "   "));
        configProvider.invalidate(RESERVED);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().prepare());

        String message = refused.getMessage();
        assertTrue(message.contains("could not reach its end state"), message);
        assertTrue(message.contains("usable claim path"), message);
        assertFalse(message.contains("catalogue entry"), "named what is there: " + message);
        assertFalse(installationStore.marker().installed());
    }

    /**
     * Completing is never adopting: a version 1 installation did not write is not activated, even where the head
     * is installation's own. The refusal of foreign documents normally stops this earlier; this calls the
     * convergence directly, as a document appearing between the two would reach it.
     */
    @Test
    void aVersionInstallationDidNotWriteIsNeverActivated() {
        catalogueStore.create(
                RESERVED,
                ControlPlaneAction.RESOURCE_TYPE,
                List.of("read", "write", "activate", "deactivate"),
                AuditActor.installation());
        lifecycleStore.create(RESERVED, ControlPlaneInstallation.baselinePolicy(), AuditActor.installation(), "seed");
        database()
                .getCollection("policy_versions")
                .updateOne(
                        Filters.and(Filters.eq("app", RESERVED), Filters.eq("policyId", BASELINE)),
                        Updates.set("audit.createdBy", "someone-else"));

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().converge(RESERVED, "apps"));

        assertTrue(
                refused.getMessage().contains("\"" + BASELINE + "\", activated by installation"), refused.getMessage());
        assertNull(lifecycleStore.findHead(RESERVED, BASELINE).orElseThrow().activeVersion());
    }

    // ── a complete installation, and concurrent starts ───────────────────────────

    @Test
    void convergingOverACompleteInstallationChangesNothing() {
        installation().prepare();
        List<List<byte[]>> before = everything();

        installation().prepare();

        assertUnchanged(before);
        assertEndState();
    }

    @Test
    void twoConcurrentStartsOnAFreshStoreEndIdentically() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            arrange();
            concurrently();
            assertEndState();
        }
    }

    @Test
    void twoConcurrentStartsCompletingAnInterruptedStartEndIdentically() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            arrange();
            interrupted(
                    attempt % 2 == 0 ? "policy_heads" : "policy_versions",
                    attempt % 2 == 0 ? NO_ACTIVATION : NO_VERSION);
            concurrently();
            assertEndState();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Runs a start that dies at the write the validator refuses, then removes the validator. */
    private void interrupted(String collection, Document refuses) {
        validator(collection, refuses);
        try {
            assertThrows(MongoWriteException.class, () -> installation().prepare());
        } finally {
            validator(collection, new Document());
        }
        configProvider.invalidate(RESERVED);
    }

    private void validator(String collection, Document validator) {
        database().runCommand(new Document("collMod", collection).append("validator", validator));
    }

    /** Two starts released together; both must complete, with no exception from either. */
    private void concurrently() throws Exception {
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> starts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                starts.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    installation().prepare();
                    return null;
                }));
            }
            for (Future<?> start : starts) {
                start.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The end state: one configuration with the mapping, the catalogue entry, the baseline v1 active, as seeded. */
    private void assertEndState() {
        assertEquals(Optional.of("apps"), configStore.storedMappingValue(RESERVED, ControlPlaneAuthorizer.APPS));
        assertTrue(
                catalogueStore.find(RESERVED, ControlPlaneAction.RESOURCE_TYPE).isPresent());
        PolicyHead head = lifecycleStore.findHead(RESERVED, BASELINE).orElseThrow();
        assertEquals(1, head.activeVersion());
        assertEquals(AuditActor.INSTALLATION, head.audit().createdBy());
        assertEquals(ControlPlaneInstallation.baselinePolicy(), head.activeContent());
        assertEquals(1, lifecycleStore.countVersions(RESERVED, BASELINE));
        assertEquals(1, database().getCollection("app_configs").countDocuments(Filters.eq("app", RESERVED)));
        assertEquals(1, database().getCollection("action_catalogue").countDocuments(Filters.eq("app", RESERVED)));
        assertEquals(1, database().getCollection("policy_heads").countDocuments(Filters.eq("app", RESERVED)));
    }

    private ControlPlaneInstallation installation() {
        return new ControlPlaneInstallation(
                ControlPlaneConfigs.config(Optional.of("apps"), Optional.of(BOOTSTRAP), RESERVED),
                installationStore,
                configStore,
                catalogueStore,
                lifecycleStore,
                foreignDocuments);
    }

    private MongoDatabase database() {
        return headRepository.mongoDatabase();
    }

    private List<List<byte[]>> everything() {
        List<List<byte[]>> all = new ArrayList<>();
        for (String collection : COLLECTIONS) {
            List<byte[]> documents = new ArrayList<>();
            for (RawBsonDocument document : database()
                    .getCollection(collection, RawBsonDocument.class)
                    .find()
                    .sort(Sorts.ascending("_id"))) {
                org.bson.ByteBuf buffer = document.getByteBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                documents.add(bytes);
            }
            all.add(documents);
        }
        return all;
    }

    private void assertUnchanged(List<List<byte[]>> before) {
        List<List<byte[]>> after = everything();
        for (int c = 0; c < COLLECTIONS.size(); c++) {
            assertEquals(before.get(c).size(), after.get(c).size(), COLLECTIONS.get(c) + " changed in size");
            for (int d = 0; d < before.get(c).size(); d++) {
                assertTrue(
                        java.util.Arrays.equals(
                                before.get(c).get(d), after.get(c).get(d)),
                        COLLECTIONS.get(c) + " document " + d + " changed");
            }
        }
    }
}
