package io.github.ricardoqmd.servicepolicy.controlplane;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.inject.Inject;

import org.bson.ByteBuf;
import org.bson.RawBsonDocument;
import org.bson.conversions.Bson;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueStore;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
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

/**
 * What startup does with the control plane (ADR-033 §6): where the claim mapping comes from before and after
 * installation, and what a store that has never been installed is seeded with.
 *
 * <p>Startup runs {@link ControlPlaneInstallation#prepare}; a failure there fails startup. These tests call
 * it on instances built with the deployment value each case needs, over the real stores.
 */
@QuarkusTest
class ControlPlaneInstallationTest {

    private static final String RESERVED = ControlPlaneTestSupport.RESERVED_APP;

    @Inject
    ControlPlaneFixtures fixtures;

    @Inject
    InstallationStore installationStore;

    @Inject
    AppConfigStore configStore;

    @Inject
    ActionCatalogueStore catalogueStore;

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    ForeignDocumentsQuery foreignDocuments;

    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void arrange() {
        fixtures.wipe();
        fixtures.seed(ControlPlaneFixtures.MINE);
        fixtures.controlPlane.installationRepository.deleteAll();
        Logger.getLogger(ControlPlaneInstallation.class.getName()).addHandler(capture);
    }

    @AfterEach
    void cleanUp() {
        Logger.getLogger(ControlPlaneInstallation.class.getName()).removeHandler(capture);
        fixtures.wipe();
        fixtures.controlPlane.installed();
    }

    // ── Not installed ────────────────────────────────────────────────────────────

    @Test
    void aServiceThatIsNotInstalledRefusesToStartWithoutTheAppsClaimPath() {
        for (Optional<String> absent : List.of(Optional.<String>empty(), Optional.of(""), Optional.of("   "))) {
            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> installation(absent, "bootstrap-admin").prepare());
            assertTrue(
                    refused.getMessage().contains("service-policy.control-plane.subject-attributes.apps"),
                    refused.getMessage());
        }
        assertTrue(configStore.find(RESERVED).isEmpty(), "a refused startup seeded something");
        assertFalse(installationStore.marker().installed());
    }

    @Test
    void seedingCreatesTheReservedConfigurationCatalogueAndActiveBaselineAndRecordsNoMarker() {
        installation(Optional.of("groups"), "bootstrap-admin").prepare();

        assertEquals(
                Map.of("apps", "groups"),
                configStore.find(RESERVED).orElseThrow().subjectAttributes());
        assertEquals(
                List.of("read", "write", "activate", "deactivate"),
                catalogueStore.find(RESERVED, "policy").orElseThrow().actions());
        PolicyHead baseline = lifecycleStore
                .findHead(RESERVED, ControlPlaneInstallation.BASELINE_POLICY_ID)
                .orElseThrow();
        assertEquals(1, baseline.activeVersion());
        assertEquals(ControlPlaneInstallation.baselinePolicy(), baseline.activeContent());
        assertEquals(AuditActor.INSTALLATION, baseline.audit().createdBy());
        assertFalse(installationStore.marker().installed(), "seeding recorded the installation marker");
    }

    /**
     * Running it again over the documents it wrote changes nothing at all, whatever claim path is configured
     * the second time. Documents it did not write are a refusal instead: see
     * {@code InstallationSeedsIntoAnEmptyReservedApplicationTest}.
     */
    @Test
    void seedingIsANoOpOverTheDocumentsItWrote() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        byte[][] before = reservedDocuments();

        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        installation(Optional.of("a_different_claim"), "bootstrap-admin").prepare();

        byte[][] after = reservedDocuments();
        for (int i = 0; i < before.length; i++) {
            assertArrayEquals(before[i], after[i], "reserved document " + i + " changed");
        }
        assertFalse(installationStore.marker().installed());
    }

    /**
     * A blank reserved identifier refuses to start before anything is written (ADR-033 §6): seeding under it
     * would install a store with a marker this build then refuses to read.
     */
    @Test
    void aBlankReservedIdentifierRefusesToStartBeforeAnythingIsWritten() {
        for (String blank : new String[] {"   ", "\t"}) {
            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> installation(Optional.of("apps"), "bootstrap-admin", blank)
                            .prepare());
            assertTrue(
                    refused.getMessage().contains("service-policy.control-plane.reserved-app is blank"),
                    refused.getMessage());
            assertTrue(configStore.find(blank).isEmpty(), "a refused startup seeded something");
            assertFalse(lifecycleStore.headExists(blank, ControlPlaneInstallation.BASELINE_POLICY_ID));
        }
        assertFalse(installationStore.marker().installed());
    }

    /** Nothing may write a marker its own reader rejects (ADR-034 §11). */
    @Test
    void theMarkerIsNeverWrittenWithABlankIdentifier() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> installationStore.recordInstalled("installer", blank));
        }
        assertFalse(installationStore.marker().installed());

        installationStore.recordInstalled("installer", RESERVED);
        assertEquals(RESERVED, installationStore.recordedReservedApp().orElseThrow(), "what is written is read");
    }

    /**
     * ADR-033 §6: a refusal, not a warning. A deployment that is not installed and names no bootstrap
     * subject accepts nobody, so the write that closes installation could never be made — and if it points
     * at an empty store by mistake, it would seed one nobody can administer.
     */
    @Test
    void aServiceThatIsNotInstalledWithoutABootstrapValueRefusesToStart() {
        for (String absent : new String[] {null, "", "   "}) {
            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> installation(Optional.of("apps"), absent).prepare());
            assertTrue(
                    refused.getMessage().contains("service-policy.control-plane.bootstrap.value"),
                    refused.getMessage());
        }
        assertTrue(configStore.find(RESERVED).isEmpty(), "a refused startup seeded something");
        assertFalse(installationStore.marker().installed());
        assertEquals(0, warnings.size(), "the refusal replaced the warning");
    }

    /** All three fields, on all four seeded documents (ADR-033 §4). */
    @Test
    void theDocumentsInstallationSeedsRecordTheInstallationIdentityAsVerified() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();

        assertAudit("app_configs", Filters.eq("app", RESERVED));
        assertAudit("action_catalogue", Filters.eq("app", RESERVED));
        assertAudit("policy_heads", Filters.eq("app", RESERVED));
        assertAudit("policy_versions", Filters.eq("app", RESERVED));
    }

    // ── Installed ────────────────────────────────────────────────────────────────

    /**
     * Once installed, the property is ignored: a different value writes nothing, logs one warning that names
     * the property and carries neither value, and changes no decision (see the HTTP half below).
     */
    @Test
    void onceInstalledADifferentPropertyWritesNothingAndWarnsWithoutValues() {
        installation(Optional.of("stored_claim_path"), "bootstrap-admin").prepare();
        installationStore.recordInstalled("installer", RESERVED);
        byte[][] before = reservedDocuments();
        warnings.clear();

        installation(Optional.of("secret_claim_path"), "bootstrap-admin").prepare();

        byte[][] after = reservedDocuments();
        for (int i = 0; i < before.length; i++) {
            assertArrayEquals(before[i], after[i], "reserved document " + i + " changed");
        }
        assertEquals(1, warnings.size(), "exactly one warning");
        String warning = message(warnings.get(0));
        assertTrue(warning.contains("service-policy.control-plane.subject-attributes.apps"), warning);
        assertFalse(warning.contains("secret_claim_path"), warning);
        assertFalse(warning.contains("stored_claim_path"), warning);

        warnings.clear();
        installation(Optional.of("stored_claim_path"), "bootstrap-admin").prepare();
        installation(Optional.empty(), "bootstrap-admin").prepare();
        assertEquals(0, warnings.size(), "a matching or absent property is not a divergence");
    }

    @Test
    @TestSecurity(user = "console-a")
    @OidcSecurity(
            claims = {
                @Claim(key = "apps", value = "[\"app-mine\"]", type = ClaimType.JSON_ARRAY),
                @Claim(key = "secret_claim_path", value = "[\"app-other\"]", type = ClaimType.JSON_ARRAY)
            })
    void onceInstalledChangingThePropertyChangesNoDecision() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        installationStore.recordInstalled("installer", RESERVED);
        fixtures.seed(ControlPlaneFixtures.OTHER);

        installation(Optional.of("secret_claim_path"), "bootstrap-admin").prepare();

        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.MINE)
                .then()
                .statusCode(200);
        given().when()
                .get("/v1/apps/{app}/policies", ControlPlaneFixtures.OTHER)
                .then()
                .statusCode(403);
    }

    /**
     * The reserved identifier is part of the installation (ADR-033 §6): a deployment configured with a
     * different one does not start, and the refusal names the property and the recorded value.
     */
    @Test
    void anInstalledStoreRefusesToStartUnderADifferentReservedApplication() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        installationStore.recordInstalled("installer", RESERVED);

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> installation(Optional.of("apps"), "bootstrap-admin", "tenant-x")
                        .prepare());

        assertTrue(refused.getMessage().contains("service-policy.control-plane.reserved-app"), refused.getMessage());
        assertTrue(refused.getMessage().contains(RESERVED), refused.getMessage());
        assertTrue(refused.getMessage().contains("tenant-x"), refused.getMessage());
    }

    /** The positive control of the refusal above: the recorded identifier starts normally. */
    @Test
    void anInstalledStoreStartsUnderTheReservedApplicationItRecorded() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        installationStore.recordInstalled("installer", RESERVED);
        warnings.clear();

        installation(Optional.of("apps"), "bootstrap-admin").prepare();

        assertEquals(0, warnings.size());
        assertEquals(RESERVED, installationStore.recordedReservedApp().orElseThrow());
    }

    /**
     * The scenario of audit-sp-003b2 H1, from the other end. A tenant administrator authors, in its own
     * application, everything the control plane is made of: the {@code policy} resource type and an active
     * policy over it that permits everyone. Under the old build, pointing
     * {@code service-policy.control-plane.reserved-app} at that application after installation handed it the
     * control plane. Now the restart that would do so does not happen, so the tenant's policies stay inert.
     */
    @Test
    void aTenantsOwnPolicyResourceTypeGainsNothingBecauseTheRestartIsRefused() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        installationStore.recordInstalled("installer", RESERVED);

        String tenant = ControlPlaneFixtures.MINE;
        configStore.create(tenant + "-mirror", new AppConfigDraft(Map.of("apps", "apps"), null), actor());
        catalogueStore.create(
                tenant + "-mirror",
                ControlPlaneAction.RESOURCE_TYPE,
                List.of("read", "write", "activate", "deactivate"),
                actor());
        lifecycleStore.create(tenant + "-mirror", ControlPlaneInstallation.baselinePolicy(), actor(), "tenant");
        lifecycleStore.activate(
                tenant + "-mirror", ControlPlaneInstallation.BASELINE_POLICY_ID, 1, 0L, actor(), "tenant");

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> installation(Optional.of("apps"), "bootstrap-admin", tenant + "-mirror")
                        .prepare());
        assertTrue(refused.getMessage().contains("service-policy.control-plane.reserved-app"), refused.getMessage());
        fixtures.configProvider.invalidate(tenant + "-mirror");
    }

    /**
     * A marker with no recorded identifier is a store an earlier build of this unreleased round installed.
     * It is not repaired and not guessed at: the service says the store predates this build.
     */
    @Test
    void anInstalledStoreWhoseMarkerRecordsNoReservedApplicationRefusesToStart() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        markerOfTheEarlierBuild(null);

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> installation(Optional.of("apps"), "bootstrap-admin").prepare());

        assertTrue(refused.getMessage().contains("predates this build"), refused.getMessage());
        assertTrue(refused.getMessage().contains("reinstalled"), refused.getMessage());
        assertTrue(installationStore.recordedReservedApp().isEmpty(), "the identifier was backfilled");
    }

    /**
     * ADR-034 §5 and §11: which shape the marker is in is read from its own schema marker, never inferred
     * from the fields it happens to carry. A shape this build does not write is refused even when it names
     * the right application, so the condition that reads the marker is exactly the one that writes it.
     */
    @Test
    void aMarkerOfAnUnrecognisedShapeRefusesToStartEvenWhenItNamesTheReservedApplication() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        markerOfTheEarlierBuild(RESERVED);

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> installation(Optional.of("apps"), "bootstrap-admin").prepare());

        assertTrue(refused.getMessage().contains("predates this build"), refused.getMessage());
        assertTrue(installationStore.recordedReservedApp().isEmpty(), "an unrecognised shape was read");
    }

    /**
     * ADR-033 §6: an installed store whose reserved application holds no {@code apps} mapping denies every
     * caller, so it is a refusal rather than the warning it used to be — a warning that said a stored
     * mapping was in force while none was.
     */
    @Test
    void anInstalledStoreWithoutAStoredMappingRefusesToStart() {
        installation(Optional.of("apps"), "bootstrap-admin").prepare();
        installationStore.recordInstalled("installer", RESERVED);
        configStore.delete(RESERVED, configStore.find(RESERVED).orElseThrow().revision());
        fixtures.configProvider.invalidate(RESERVED);
        warnings.clear();

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> installation(Optional.of("apps"), "bootstrap-admin").prepare());

        assertTrue(refused.getMessage().contains("no claim mapping"), refused.getMessage());
        assertTrue(refused.getMessage().contains("in the store directly"), refused.getMessage());
        // the minimal shape the API can maintain afterwards (a document without revision answers a 412 forever)
        assertTrue(refused.getMessage().contains("schemaVersion 1"), refused.getMessage());
        assertTrue(refused.getMessage().contains("revision as a 64-bit integer"), refused.getMessage());
        assertFalse(
                refused.getMessage().contains("service-policy.control-plane.reserved-app"),
                "advised changing the reserved identifier, which the marker forbids: " + refused.getMessage());
        assertEquals(0, warnings.size(), "the false divergence warning was emitted");
    }

    /**
     * A stored {@code apps} that is not a non-empty string cannot resolve a claim path, so it is as good as
     * absent: a refusal, and never the warning that says a stored mapping is in force.
     */
    @Test
    void aStoredMappingThatIsNotAUsableClaimPathRefusesWithoutTheWarning() {
        for (Object unusable : new Object[] {5, List.of("apps"), "", "   "}) {
            installation(Optional.of("apps"), "bootstrap-admin").prepare();
            installationStore.recordInstalled("installer", RESERVED);
            headRepository
                    .mongoDatabase()
                    .getCollection("app_configs")
                    .updateOne(Filters.eq("app", RESERVED), Updates.set("subjectAttributes.apps", unusable));
            fixtures.configProvider.invalidate(RESERVED);
            warnings.clear();

            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> installation(Optional.of("apps"), "bootstrap-admin").prepare(),
                    String.valueOf(unusable));

            assertTrue(refused.getMessage().contains("not a usable claim path"), refused.getMessage());
            assertFalse(
                    refused.getMessage().contains("service-policy.control-plane.reserved-app"), refused.getMessage());
            assertEquals(0, warnings.size(), "warned for " + unusable);
            fixtures.wipe();
            fixtures.controlPlane.installationRepository.deleteAll();
        }
    }

    /** The positive control: a usable stored claim path that differs warns exactly once, naming only the property. */
    @Test
    void aUsableStoredMappingThatDiffersStillWarnsOnce() {
        installation(Optional.of("stored_claim_path"), "bootstrap-admin").prepare();
        installationStore.recordInstalled("installer", RESERVED);
        warnings.clear();

        installation(Optional.of("apps"), "bootstrap-admin").prepare();

        assertEquals(1, warnings.size());
        String warning = message(warnings.get(0));
        assertTrue(warning.contains("service-policy.control-plane.subject-attributes.apps"), warning);
        assertFalse(warning.contains("stored_claim_path"), warning);
        assertFalse(warning.contains("apps\""), warning);
    }

    /**
     * Only the shape an earlier build wrote "predates this build". A marker of this build's shape whose
     * identifier is missing, blank or not a string — or one of a shape this build does not read — is refused
     * in its own words, and never with a {@code ClassCastException}.
     */
    @Test
    void anUnreadableMarkerIsRefusedAsWhatItIs() {
        for (Object identifier : new Object[] {null, "   ", 5, List.of(RESERVED)}) {
            installation(Optional.of("apps"), "bootstrap-admin").prepare();
            marker(2, identifier);

            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> installation(Optional.of("apps"), "bootstrap-admin").prepare(),
                    String.valueOf(identifier));

            assertTrue(refused.getMessage().contains("missing, blank or not a string"), refused.getMessage());
            assertFalse(refused.getMessage().contains("predates"), refused.getMessage());
            fixtures.controlPlane.installationRepository.deleteAll();
        }
        for (Object schemaVersion : new Object[] {3, 2L, 2.0d, "2", null}) {
            installation(Optional.of("apps"), "bootstrap-admin").prepare();
            marker(schemaVersion, RESERVED);

            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> installation(Optional.of("apps"), "bootstrap-admin").prepare(),
                    String.valueOf(schemaVersion));

            assertTrue(
                    refused.getMessage().contains("schemaVersion is not one this build reads"), refused.getMessage());
            assertFalse(refused.getMessage().contains("predates"), refused.getMessage());
            fixtures.controlPlane.installationRepository.deleteAll();
        }
    }

    /** The divergence warning is reachable only where a mapping exists — the case above is the other half. */
    @Test
    void theDivergenceWarningIsEmittedOnlyWhenAMappingIsStored() {
        installation(Optional.of("stored_claim_path"), "bootstrap-admin").prepare();
        installationStore.recordInstalled("installer", RESERVED);
        warnings.clear();

        installation(Optional.of("secret_claim_path"), "bootstrap-admin").prepare();
        assertEquals(1, warnings.size(), "a stored mapping that differs is one warning");

        configStore.replace(
                RESERVED,
                new AppConfigDraft(Map.of("unit", "unit"), null),
                configStore.find(RESERVED).orElseThrow().revision(),
                actor());
        fixtures.configProvider.invalidate(RESERVED);
        warnings.clear();

        assertThrows(
                IllegalStateException.class,
                () -> installation(Optional.of("secret_claim_path"), "bootstrap-admin")
                        .prepare());
        assertEquals(0, warnings.size(), "a configuration without the apps mapping warned instead of refusing");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * The marker as an earlier build of this unreleased round wrote it: shape 1. {@code reservedApp} is
     * {@code null} for the marker that build actually wrote, and a value for the hand-made mixture that
     * shows the shape is decided by the schema marker rather than by the field's presence.
     */
    private void markerOfTheEarlierBuild(String reservedApp) {
        marker(1, reservedApp);
    }

    /** A marker written by hand; a {@code null} schema version or identifier leaves that field out. */
    private void marker(Object schemaVersion, Object reservedApp) {
        org.bson.Document marker = new org.bson.Document("_id", "control-plane")
                .append("installedAt", "2026-09-17T00:00:00Z")
                .append("installedBy", "installer");
        if (schemaVersion != null) {
            marker.append("schemaVersion", schemaVersion);
        }
        if (reservedApp != null) {
            marker.append("reservedApp", reservedApp);
        }
        headRepository.mongoDatabase().getCollection("installation").insertOne(marker);
    }

    private void assertAudit(String collection, Bson filter) {
        org.bson.Document stored = headRepository
                .mongoDatabase()
                .getCollection(collection)
                .find(filter)
                .first();
        assertNotNull(stored, "no document in " + collection);
        org.bson.Document audit = stored.get("audit", org.bson.Document.class);
        assertEquals(AuditActor.INSTALLATION, audit.getString("createdBy"), collection + " createdBy");
        assertEquals(AuditActor.INSTALLATION, audit.getString("subject"), collection + " subject");
        assertEquals("VERIFIED", audit.getString("subjectProvenance"), collection + " subjectProvenance");
    }

    private ControlPlaneInstallation installation(Optional<String> appsClaim, String bootstrapValue) {
        return installation(appsClaim, bootstrapValue, RESERVED);
    }

    private ControlPlaneInstallation installation(
            Optional<String> appsClaim, String bootstrapValue, String reservedApp) {
        return new ControlPlaneInstallation(
                ControlPlaneConfigs.config(appsClaim, Optional.ofNullable(bootstrapValue), reservedApp),
                installationStore,
                configStore,
                catalogueStore,
                lifecycleStore,
                foreignDocuments);
    }

    /** The reserved application's configuration, catalogue entry, baseline head and baseline versions, raw. */
    private byte[][] reservedDocuments() {
        Bson reserved = Filters.eq("app", RESERVED);
        return new byte[][] {
            raw("app_configs", reserved),
            raw("action_catalogue", reserved),
            raw("policy_heads", reserved),
            raw("policy_versions", reserved)
        };
    }

    private byte[] raw(String collection, Bson filter) {
        RawBsonDocument document = headRepository
                .mongoDatabase()
                .getCollection(collection, RawBsonDocument.class)
                .find(filter)
                .first();
        assertNotNull(document, "no document in " + collection);
        ByteBuf buffer = document.getByteBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static AuditActor actor() {
        return AuditActor.verified("operator");
    }

    private static String message(LogRecord record) {
        return record instanceof ExtLogRecord extended ? extended.getFormattedMessage() : record.getMessage();
    }
}
