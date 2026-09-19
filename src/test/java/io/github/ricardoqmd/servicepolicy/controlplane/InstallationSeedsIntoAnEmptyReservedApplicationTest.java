package io.github.ricardoqmd.servicepolicy.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import io.github.ricardoqmd.servicepolicy.ControlPlaneTestSupport;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueStore;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.ForeignDocumentsQuery;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Installation seeds into an empty reserved application, or it does not seed (ADR-033 §6).
 *
 * <p>The scenario that made this necessary: a store written by the previous version, where a caller holding the
 * old global marker authored, in the application that later becomes reserved, a permissive policy with the
 * baseline's own id. Seeding used to leave it as it found it — and so adopted it as the control plane's rule.
 * Now a service that is not installed and finds anything there that installation did not write refuses to
 * start, writes nothing, names each document in the way, and states only the recovery that applies to it.
 *
 * <p>"Installation wrote it" is read from the audit installation records on its own documents, all three
 * fields at once; never from what a document says.
 */
@QuarkusTest
class InstallationSeedsIntoAnEmptyReservedApplicationTest {

    private static final String RESERVED = ControlPlaneTestSupport.RESERVED_APP;

    /** How the previous version recorded a write: the calling credential, and nothing beside it. */
    private static final AuditActor PREVIOUS_VERSION = new AuditActor("legacy-console", null, null);

    private static final List<String> COLLECTIONS =
            List.of("app_configs", "action_catalogue", "policy_heads", "policy_versions", "installation");

    private static final List<String> VERBS = List.of("read", "write", "activate", "deactivate");

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

    @BeforeEach
    void arrange() {
        fixtures.wipe();
        fixtures.seed(ControlPlaneFixtures.MINE);
        fixtures.controlPlane.installationRepository.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        fixtures.wipe();
        fixtures.controlPlane.installed();
    }

    // ── the audit's scenario ─────────────────────────────────────────────────────

    @Test
    void aPlantedPermissiveBaselineRefusesToStartAndNamesIt() {
        plantPermissiveBaseline();
        List<List<byte[]>> before = everything();

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().prepare());

        String message = refused.getMessage();
        assertTrue(message.contains("service-policy.control-plane.reserved-app is \"" + RESERVED + "\""), message);
        assertTrue(message.contains("policy \"control-plane-baseline\""), message);
        assertTrue(message.contains("catalogue entry \"policy\""), message);
        assertTrue(message.contains("an application that does not exist yet"), message);
        assertTrue(message.contains("cannot be removed through the API"), message);
        assertFalse(message.contains("delete them"), "offered a recovery the API cannot perform: " + message);
        assertUnchanged(before);
    }

    /** Deactivating it with the previous version does not help: the check does not read what a document says. */
    @Test
    void aPlantedBaselineThatWasDeactivatedStillRefuses() {
        plantPermissiveBaseline();
        lifecycleStore.deactivate(RESERVED, ControlPlaneInstallation.BASELINE_POLICY_ID, 1L, PREVIOUS_VERSION, null);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().prepare());

        assertTrue(refused.getMessage().contains("policy \"control-plane-baseline\""), refused.getMessage());
        assertFalse(installationStore.marker().installed());
    }

    /** A document identical to the baseline is still not one this installation wrote. */
    @Test
    void aPolicyThatLooksExactlyLikeTheBaselineIsStillForeign() {
        catalogueStore.create(RESERVED, ControlPlaneAction.RESOURCE_TYPE, VERBS, AuditActor.installation());
        lifecycleStore.create(RESERVED, ControlPlaneInstallation.baselinePolicy(), PREVIOUS_VERSION, "planted");
        lifecycleStore.activate(
                RESERVED, ControlPlaneInstallation.BASELINE_POLICY_ID, 1, 0L, PREVIOUS_VERSION, "planted");

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().prepare());

        assertTrue(refused.getMessage().contains("policy \"control-plane-baseline\""), refused.getMessage());
        assertFalse(refused.getMessage().contains("catalogue entry"), "installation's own entry was named");
    }

    // ── each of the other kinds, alone ───────────────────────────────────────────

    @Test
    void aForeignConfigurationAloneRefusesAndOffersBothWaysOut() {
        configStore.create(RESERVED, new AppConfigDraft(Map.of("apps", "apps"), null), PREVIOUS_VERSION);
        List<List<byte[]>> before = everything();

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().prepare());

        String message = refused.getMessage();
        assertTrue(message.contains("the configuration"), message);
        assertTrue(message.contains("delete them with the version that wrote them"), message);
        assertTrue(message.contains("an application that does not exist yet"), message);
        assertFalse(message.contains("policy \""), message);
        assertUnchanged(before);
    }

    @Test
    void aForeignCatalogueEntryAloneRefusesAndOffersBothWaysOut() {
        catalogueStore.create(RESERVED, "invoice", List.of("read"), PREVIOUS_VERSION);
        List<List<byte[]>> before = everything();

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().prepare());

        String message = refused.getMessage();
        assertTrue(message.contains("catalogue entry \"invoice\""), message);
        assertTrue(message.contains("delete them with the version that wrote them"), message);
        assertUnchanged(before);
    }

    /** A version appended beside installation's own baseline, with the head untouched. */
    @Test
    void aForeignPolicyVersionAloneRefusesAndOffersOnlyAnotherIdentifier() {
        installation().prepare();
        Document version = rawVersion(1);
        version.put("_id", new ObjectId());
        version.put("version", 2);
        version.put("audit", new Document("createdBy", "legacy-console").append("createdAt", "2026-09-01T00:00:00Z"));
        headRepository.mongoDatabase().getCollection("policy_versions").insertOne(version);
        List<List<byte[]>> before = everything();

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().prepare());

        String message = refused.getMessage();
        assertTrue(message.contains("policy \"control-plane-baseline\""), message);
        assertFalse(message.contains("the configuration"), "installation's own configuration was named");
        assertFalse(message.contains("delete them"), message);
        assertUnchanged(before);
    }

    /**
     * A caller may declare any subject, the installation identity included; it is recorded with the caller's
     * own credential and {@code DECLARED}, so the subject alone never makes a document installation's.
     */
    @Test
    void aDocumentThatDeclaresTheInstallationSubjectIsStillForeign() {
        configStore.create(
                RESERVED,
                new AppConfigDraft(Map.of("apps", "apps"), null),
                AuditActor.declaring("legacy-console", AuditActor.INSTALLATION));

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> installation().prepare());

        assertTrue(refused.getMessage().contains("the configuration"), refused.getMessage());
    }

    // ── what the refusal says ────────────────────────────────────────────────────

    @Test
    void theRefusalNamesTenAndCountsTheRest() {
        for (int i = 0; i < 12; i++) {
            catalogueStore.create(RESERVED, "type-%02d".formatted(i), List.of("read"), PREVIOUS_VERSION);
        }

        String message = assertThrows(
                        IllegalStateException.class, () -> installation().prepare())
                .getMessage();

        assertTrue(message.contains("catalogue entry \"type-09\"; and 2 more"), message);
        assertFalse(message.contains("type-10"), message);
        assertFalse(message.contains("type-11"), message);
    }

    @Test
    void theRefusalCarriesNoDocumentContents() {
        configStore.create(RESERVED, new AppConfigDraft(Map.of("apps", "secret_claim_zz"), null), PREVIOUS_VERSION);
        catalogueStore.create(RESERVED, ControlPlaneAction.RESOURCE_TYPE, VERBS, PREVIOUS_VERSION);
        lifecycleStore.create(RESERVED, permissive("secret-value-zz"), PREVIOUS_VERSION, "planted");

        String message = assertThrows(
                        IllegalStateException.class, () -> installation().prepare())
                .getMessage();

        assertFalse(message.contains("secret_claim_zz"), message);
        assertFalse(message.contains("secret-value-zz"), message);
        assertFalse(message.contains("legacy-console"), message);
    }

    // ── what still starts ────────────────────────────────────────────────────────

    /** A store written by an earlier run of this installation: its own documents are not in the way. */
    @Test
    void aReservedApplicationHoldingOnlyWhatInstallationWroteStartsAndChangesNothing() {
        installation().prepare();
        List<List<byte[]>> before = everything();

        installation().prepare();

        assertUnchanged(before);
        assertTrue(foreignDocuments.in(RESERVED).none());
    }

    @Test
    void aFreshStoreStartsAndSeeds() {
        installation().prepare();

        assertTrue(lifecycleStore
                .findHead(RESERVED, ControlPlaneInstallation.BASELINE_POLICY_ID)
                .isPresent());
        assertTrue(configStore.find(RESERVED).isPresent());
        assertFalse(installationStore.marker().installed());
    }

    /** What another application holds is never in the way, however it was written. */
    @Test
    void documentsOfOtherApplicationsAreNotInTheWay() {
        catalogueStore.create("app-previous", ControlPlaneAction.RESOURCE_TYPE, VERBS, PREVIOUS_VERSION);

        installation().prepare();

        assertTrue(configStore.find(RESERVED).isPresent());
        fixtures.configProvider.invalidate("app-previous");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The audit's plant: the {@code policy} vocabulary and a {@code PERMIT} policy with no rules, active. */
    private void plantPermissiveBaseline() {
        catalogueStore.create(RESERVED, ControlPlaneAction.RESOURCE_TYPE, VERBS, PREVIOUS_VERSION);
        lifecycleStore.create(RESERVED, permissive(null), PREVIOUS_VERSION, "planted");
        lifecycleStore.activate(
                RESERVED, ControlPlaneInstallation.BASELINE_POLICY_ID, 1, 0L, PREVIOUS_VERSION, "planted");
    }

    /** {@code control-plane-baseline} that permits everything; with a rule naming {@code marker} when given. */
    private static Policy permissive(String marker) {
        List<Rule> rules = marker == null
                ? List.of()
                : List.of(new Rule(
                        "r",
                        Effect.PERMIT,
                        new Comparison(Operator.EQ, new AttributeRef("subject.id"), new Literal(marker))));
        return new Policy(
                ControlPlaneInstallation.BASELINE_POLICY_ID,
                1,
                ControlPlaneAction.RESOURCE_TYPE,
                VERBS,
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.PERMIT,
                rules);
    }

    private ControlPlaneInstallation installation() {
        return new ControlPlaneInstallation(
                ControlPlaneConfigs.config(Optional.of("apps"), Optional.of("bootstrap-admin"), RESERVED),
                installationStore,
                configStore,
                catalogueStore,
                lifecycleStore,
                foreignDocuments);
    }

    private Document rawVersion(int version) {
        return headRepository
                .mongoDatabase()
                .getCollection("policy_versions")
                .find(Filters.and(
                        Filters.eq("app", RESERVED),
                        Filters.eq("policyId", ControlPlaneInstallation.BASELINE_POLICY_ID),
                        Filters.eq("version", version)))
                .first();
    }

    /** Every document of every collection installation could touch, raw, in a stable order. */
    private List<List<byte[]>> everything() {
        List<List<byte[]>> all = new ArrayList<>();
        for (String collection : COLLECTIONS) {
            List<byte[]> documents = new ArrayList<>();
            for (RawBsonDocument document : headRepository
                    .mongoDatabase()
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

    /** A refused start wrote nothing and changed nothing, in any collection, for any application. */
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
