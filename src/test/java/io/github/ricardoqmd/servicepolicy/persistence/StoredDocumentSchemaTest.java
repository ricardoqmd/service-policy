package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;

import org.bson.ByteBuf;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import io.github.ricardoqmd.servicepolicy.ActionCatalogueTestSupport;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.HeadStatus;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionFailedException;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The schema marker of ADR-034, at the persistence boundary: what the current code writes, how a
 * document written before the marker existed reads, and that every read of a stored document refuses
 * a marker this build does not know.
 *
 * <p>Documents are inspected and seeded as raw BSON, through the collections themselves rather than
 * the document classes. That is the point: the classes default the marker to 1, so a document built
 * from one can neither show what was actually stored nor stand for one stored before the field
 * existed.
 */
@QuarkusTest
class StoredDocumentSchemaTest {

    private static final String APP = "schema-app";
    private static final int UNKNOWN = 999;

    @Inject
    PolicyLifecycleStore store;

    @Inject
    ActionCatalogueStore catalogueStore;

    @Inject
    ActionCatalogueResolver resolver;

    @Inject
    AppConfigStore configStore;

    @Inject
    AppConfigProvider provider;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @Inject
    AppConfigRepository configRepository;

    private final PolicyDocumentMapper contentMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    @BeforeEach
    void clean() {
        wipe();
    }

    @AfterEach
    void wipe() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
        configRepository.deleteAll();
        provider.invalidate(APP);
    }

    // ── 1. What the current code writes, by exact shape ─────────────────────────

    @Test
    void createWritesAVersionWithSchemaVersionOne() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");

        store.create(APP, policy("p-new", 1), "tester", "first");

        Document stored = stored("policy_versions", Filters.eq("policyId", "p-new"));
        assertEquals(Set.of("_id", "app", "policyId", "version", "content", "audit", "schemaVersion"), stored.keySet());
        assertEquals(1, stored.get("schemaVersion"));
    }

    /** A new head holds no copied content, so it carries its own marker and no content marker (ADR-034 §9). */
    @Test
    void createWritesAHeadWithItsOwnMarkerAndNoContentMarker() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");

        store.create(APP, policy("p-new", 1), "tester", "first");

        Document stored = stored("policy_heads", Filters.eq("policyId", "p-new"));
        assertEquals(
                Set.of(
                        "_id",
                        "policyId",
                        "app",
                        "resourceType",
                        "activeVersion",
                        "activeContent",
                        "revision",
                        "audit",
                        "schemaVersion"),
                stored.keySet());
        assertEquals(1, stored.get("schemaVersion"));
    }

    @Test
    void appendWritesTheNewVersionWithSchemaVersionOne() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        store.create(APP, policy("p-new", 1), "tester", "first");

        store.append(APP, "p-new", policy("p-new", 2), 0L, "tester", "second");

        Document stored =
                stored("policy_versions", Filters.and(Filters.eq("policyId", "p-new"), Filters.eq("version", 2)));
        assertEquals(Set.of("_id", "app", "policyId", "version", "content", "audit", "schemaVersion"), stored.keySet());
        assertEquals(1, stored.get("schemaVersion"));
    }

    @Test
    void createWritesAConfigurationWithSchemaVersionOne() {
        configStore.create(APP, draft(), "tester");

        Document stored = stored("app_configs", Filters.eq("app", APP));
        assertEquals(
                Set.of("_id", "app", "subjectAttributes", "pip", "revision", "audit", "schemaVersion"),
                stored.keySet());
        assertEquals(1, stored.get("schemaVersion"));
    }

    @Test
    void createWritesACatalogueEntryWithSchemaVersionOne() {
        catalogueStore.create(APP, "invoice", List.of("read", "approve"), "tester");

        Document stored = stored("action_catalogue", Filters.eq("resourceType", "invoice"));
        assertEquals(
                Set.of("_id", "app", "resourceType", "actions", "revision", "audit", "schemaVersion"), stored.keySet());
        assertEquals(1, stored.get("schemaVersion"));
    }

    // ── 2. A document stored without the marker is shape 1, and nothing backfills it ──

    @Test
    void aHeadAndVersionsWithoutMarkersReadAsShapeOneAndStayWithoutThem() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        Policy v1 = policy("p-old", 1);
        collection("policy_heads").insertOne(unmarkedHead("p-old", 1, v1, 1L));
        collection("policy_versions").insertOne(unmarkedVersion("p-old", v1));

        assertEquals(v1, store.findHead(APP, "p-old").orElseThrow().activeContent());
        assertEquals(1, store.findHeads(APP, HeadStatus.ALL, 0, 10).size());
        assertEquals(v1, store.findVersion(APP, "p-old", 1).orElseThrow().content());
        assertEquals(1, store.findVersions(APP, "p-old", 0, 10).size());
        assertEquals(List.of(v1), store.activePoliciesFor(APP, "document"));

        // The write paths read them too: append reads the latest version, activate the one it copies.
        int appended = store.append(APP, "p-old", policy("p-old", 2), 1L, "tester", "second");
        store.activate(APP, "p-old", 1, 2L, "tester", "back to one");

        assertEquals(2, appended);
        assertFalse(stored("policy_versions", Filters.eq("version", 1)).containsKey("schemaVersion"));
        Document head = stored("policy_heads", Filters.eq("policyId", "p-old"));
        assertFalse(head.containsKey("schemaVersion"), "activation must not backfill the head's own marker");
        assertEquals(1, head.get("activeContentSchemaVersion"));

        // The third head writer: deactivation must not backfill the head's own marker either.
        store.deactivate(APP, "p-old", 3L, "tester", "retire");

        Document deactivated = stored("policy_heads", Filters.eq("policyId", "p-old"));
        assertFalse(deactivated.containsKey("schemaVersion"), "deactivation must not backfill the head's own marker");
        assertFalse(deactivated.containsKey("activeContentSchemaVersion"));
    }

    @Test
    void aConfigurationWithoutAMarkerReadsAsShapeOneAndAReplaceDoesNotAddIt() {
        collection("app_configs").insertOne(unmarkedConfig());

        assertEquals(
                Map.of("rol", "realm_access.roles"),
                configStore.find(APP).orElseThrow().subjectAttributes());
        assertEquals(1L, provider.forApp(APP).orElseThrow().revision());

        AppConfig replaced = configStore.replace(APP, draft(), 1L, "tester");

        assertEquals(2L, replaced.revision());
        assertFalse(stored("app_configs", Filters.eq("app", APP)).containsKey("schemaVersion"));
    }

    @Test
    void aCatalogueEntryWithoutAMarkerReadsAsShapeOneAndAReplaceDoesNotAddIt() {
        collection("action_catalogue").insertOne(unmarkedEntry("document", List.of("read")));

        assertEquals(
                List.of("read"),
                catalogueStore.find(APP, "document").orElseThrow().actions());
        assertEquals(1, catalogueStore.list(APP).size());
        assertEquals(
                List.of("read"),
                resolver.resolve(APP, withActions(policy("p-any", 1), List.of("*")))
                        .actions());

        ActionCatalogueEntry replaced =
                catalogueStore.replace(APP, "document", List.of("read", "approve"), 1L, "tester");

        assertEquals(2L, replaced.revision());
        assertFalse(stored("action_catalogue", Filters.eq("resourceType", "document"))
                .containsKey("schemaVersion"));
    }

    // ── 3. A marker this build does not know fails the read, at every read site ──

    @Test
    void findHeadsRefusesAnUnknownHeadMarker() {
        collection("policy_heads").insertOne(unmarkedHead("p-x", null, null, 0L).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> store.findHeads(APP, HeadStatus.ALL, 0, 10));
    }

    @Test
    void findHeadRefusesAnUnknownHeadMarker() {
        collection("policy_heads").insertOne(unmarkedHead("p-x", null, null, 0L).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> store.findHead(APP, "p-x"));
    }

    @Test
    void activePoliciesForRefusesAnUnknownHeadMarker() {
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", 1, policy("p-x", 1), 1L).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> store.activePoliciesFor(APP, "document"));
    }

    @Test
    void aHeadWhoseCopiedContentCarriesAnUnknownMarkerIsRefused() {
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", 1, policy("p-x", 1), 1L)
                        .append("schemaVersion", 1)
                        .append("activeContentSchemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> store.findHead(APP, "p-x"));
    }

    @Test
    void findVersionsRefusesAnUnknownVersionMarker() {
        collection("policy_versions")
                .insertOne(unmarkedVersion("p-x", policy("p-x", 1)).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> store.findVersions(APP, "p-x", 0, 10));
    }

    @Test
    void findVersionRefusesAnUnknownVersionMarker() {
        collection("policy_versions")
                .insertOne(unmarkedVersion("p-x", policy("p-x", 1)).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> store.findVersion(APP, "p-x", 1));
    }

    /**
     * Refused before the compare-and-swap, so the head's revision does not move: the same {@code If-Match}
     * meets the same refusal on every retry, instead of each retry consuming a revision.
     */
    @Test
    void appendRefusesALatestVersionWithAnUnknownMarkerBeforeTheRevisionMoves() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        collection("policy_heads").insertOne(unmarkedHead("p-x", null, null, 0L));
        collection("policy_versions")
                .insertOne(unmarkedVersion("p-x", policy("p-x", 1)).append("schemaVersion", UNKNOWN));
        byte[] before = rawHead("p-x");

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThrows(
                    StoredDocumentSchemaException.class,
                    () -> store.append(APP, "p-x", policy("p-x", 2), 0L, "tester", "second"));

            assertEquals(
                    0L, stored("policy_heads", Filters.eq("policyId", "p-x")).get("revision"));
            assertArrayEquals(before, rawHead("p-x"), "attempt " + attempt);
            assertEquals(1, collection("policy_versions").countDocuments(), "attempt " + attempt);
        }
    }

    @Test
    void activateRefusesAVersionWithAnUnknownMarkerBeforeTouchingTheHead() {
        collection("policy_heads").insertOne(unmarkedHead("p-x", null, null, 0L));
        collection("policy_versions")
                .insertOne(unmarkedVersion("p-x", policy("p-x", 1)).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> store.activate(APP, "p-x", 1, 0L, "tester", "go"));
        assertNull(stored("policy_heads", Filters.eq("policyId", "p-x")).get("activeVersion"));
    }

    @Test
    void catalogueFindRefusesAnUnknownMarker() {
        collection("action_catalogue")
                .insertOne(unmarkedEntry("document", List.of("read")).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> catalogueStore.find(APP, "document"));
    }

    @Test
    void catalogueListRefusesAnUnknownMarker() {
        collection("action_catalogue")
                .insertOne(unmarkedEntry("document", List.of("read")).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> catalogueStore.list(APP));
    }

    @Test
    void catalogueReplaceAndDeleteRefuseAnUnknownMarkerAndLeaveItStored() {
        collection("action_catalogue")
                .insertOne(unmarkedEntry("document", List.of("read")).append("schemaVersion", UNKNOWN));

        assertThrows(
                StoredDocumentSchemaException.class,
                () -> catalogueStore.replace(APP, "document", List.of("read", "approve"), 1L, "tester"));
        assertThrows(StoredDocumentSchemaException.class, () -> catalogueStore.delete(APP, "document", 1L));
        assertEquals(
                List.of("read"),
                stored("action_catalogue", Filters.eq("resourceType", "document"))
                        .get("actions"));
    }

    @Test
    void theResolverRefusesAnUnknownCatalogueMarker() {
        collection("action_catalogue")
                .insertOne(unmarkedEntry("document", List.of("read")).append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> resolver.resolve(APP, policy("p-any", 1)));
    }

    @Test
    void configurationFindRefusesAnUnknownMarker() {
        collection("app_configs").insertOne(unmarkedConfig().append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> configStore.find(APP));
    }

    @Test
    void configurationReplaceAndDeleteRefuseAnUnknownMarkerAndLeaveItStored() {
        collection("app_configs").insertOne(unmarkedConfig().append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> configStore.replace(APP, draft(), 1L, "tester"));
        assertThrows(StoredDocumentSchemaException.class, () -> configStore.delete(APP, 1L));
        assertEquals(1L, stored("app_configs", Filters.eq("app", APP)).get("revision"));
    }

    /** Refused and not remembered: the second read fails the same way instead of hitting the cache. */
    @Test
    void theConfigurationProviderRefusesAnUnknownMarkerOnEveryRead() {
        collection("app_configs").insertOne(unmarkedConfig().append("schemaVersion", UNKNOWN));

        assertThrows(StoredDocumentSchemaException.class, () -> provider.forApp(APP));
        assertThrows(StoredDocumentSchemaException.class, () -> provider.forApp(APP));
    }

    // ── 4. Activation carries the copied content's marker ──────────────────────

    /**
     * Each activation below starts from a head without the content marker, so what the assertion reads is
     * what that activation wrote. A new head already has none (ADR-034 §9); before the second activation
     * the field is dropped, because the first one wrote it.
     */
    @Test
    void activatingAVersionWritesItsMarkerAndActivatingAnotherRewritesIt() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        store.create(APP, policy("p-act", 1), "tester", "first");

        dropContentMarker("p-act");
        PolicyHead first = store.activate(APP, "p-act", 1, 0L, "tester", "one");

        Document afterFirst = stored("policy_heads", Filters.eq("policyId", "p-act"));
        assertEquals(1, afterFirst.get("activeVersion"));
        assertEquals(1, afterFirst.get("activeContentSchemaVersion"));

        store.append(APP, "p-act", policy("p-act", 2), first.revision(), "tester", "second");
        long revision = store.findHead(APP, "p-act").orElseThrow().revision();
        dropContentMarker("p-act");
        store.activate(APP, "p-act", 2, revision, "tester", "two");

        Document afterSecond = stored("policy_heads", Filters.eq("policyId", "p-act"));
        assertEquals(2, afterSecond.get("activeVersion"));
        assertEquals(1, afterSecond.get("activeContentSchemaVersion"));
    }

    // ── 5. A build does not write to a head whose shape it does not know (ADR-034 §8) ──

    /*
     * Every assertion in this section is on the STORED document, compared byte for byte, and not on
     * what the call returned: before the marker went into the write condition, activate and deactivate
     * already refused — after they had written.
     */

    @Test
    void appendLeavesAHeadOfAnUnknownShapeByteForByteUnchanged() {
        seedUnknownShapeHead("p-x");
        byte[] before = rawHead("p-x");

        assertThrows(
                StoredDocumentSchemaException.class,
                () -> store.append(APP, "p-x", policy("p-x", 3), 1L, "tester", "third"));

        assertArrayEquals(before, rawHead("p-x"));
        assertEquals(2, collection("policy_versions").countDocuments(Filters.eq("policyId", "p-x")));
    }

    @Test
    void activateLeavesAHeadOfAnUnknownShapeByteForByteUnchanged() {
        seedUnknownShapeHead("p-x");
        byte[] before = rawHead("p-x");

        assertThrows(StoredDocumentSchemaException.class, () -> store.activate(APP, "p-x", 2, 1L, "tester", "two"));

        assertArrayEquals(before, rawHead("p-x"));
    }

    @Test
    void deactivateLeavesAHeadOfAnUnknownShapeByteForByteUnchanged() {
        seedUnknownShapeHead("p-x");
        byte[] before = rawHead("p-x");

        assertThrows(StoredDocumentSchemaException.class, () -> store.deactivate(APP, "p-x", 1L, "tester", "off"));

        assertArrayEquals(before, rawHead("p-x"));
    }

    /**
     * The caller's {@code If-Match} is still the head's revision after a refusal: once the head is back
     * to a shape this build knows, the very same request succeeds. Before, the write had landed, so the
     * retry could only ever meet a revision it did not hold.
     */
    @Test
    void aRefusedWriteLeavesTheRevisionTheCallerHolds() {
        List<Runnable> writes = List.of(
                () -> store.append(APP, "p-x", policy("p-x", 3), 1L, "tester", "third"),
                () -> store.activate(APP, "p-x", 2, 1L, "tester", "two"),
                () -> store.deactivate(APP, "p-x", 1L, "tester", "off"));
        for (Runnable write : writes) {
            wipe();
            seedUnknownShapeHead("p-x");

            assertThrows(StoredDocumentSchemaException.class, write::run);
            assertEquals(
                    1L, stored("policy_heads", Filters.eq("policyId", "p-x")).get("revision"));

            collection("policy_heads").updateOne(Filters.eq("policyId", "p-x"), Updates.set("schemaVersion", 1));
            write.run();
            assertEquals(
                    2L, stored("policy_heads", Filters.eq("policyId", "p-x")).get("revision"));
        }
    }

    /**
     * Activation conditions only the head's own marker: it replaces the content and its marker whole, so
     * a content marker this build does not know is overwritten rather than an obstacle.
     */
    @Test
    void activateReplacesContentWhoseMarkerThisBuildDoesNotKnow() {
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", 1, policy("p-x", 1), 1L)
                        .append("schemaVersion", 1)
                        .append("activeContentSchemaVersion", UNKNOWN));
        collection("policy_versions").insertOne(unmarkedVersion("p-x", policy("p-x", 1)));
        collection("policy_versions").insertOne(unmarkedVersion("p-x", policy("p-x", 2)));

        PolicyHead head = store.activate(APP, "p-x", 2, 1L, "tester", "two");

        assertEquals(2, head.activeVersion());
        Document stored = stored("policy_heads", Filters.eq("policyId", "p-x"));
        assertEquals(2, stored.get("activeVersion"));
        assertEquals(1, stored.get("activeContentSchemaVersion"));
    }

    /**
     * A stale {@code If-Match} on a head this build can read in its own shape, holding content copied in a
     * shape it cannot, is answered with the head's revision: reporting a revision interprets no content.
     * With that revision, the activation that replaces the content goes through — while every read that
     * returns the content still refuses it.
     */
    @Test
    void aStaleIfMatchOnAHeadWithContentOfAnUnknownShapeReportsTheRevision() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", 1, policy("p-x", 1), 5L)
                        .append("schemaVersion", 1)
                        .append("activeContentSchemaVersion", UNKNOWN));
        collection("policy_versions").insertOne(unmarkedVersion("p-x", policy("p-x", 1)));
        collection("policy_versions").insertOne(unmarkedVersion("p-x", policy("p-x", 2)));
        byte[] before = rawHead("p-x");

        List<Executable> staleWrites = List.of(
                () -> store.append(APP, "p-x", policy("p-x", 3), 3L, "tester", "third"),
                () -> store.activate(APP, "p-x", 2, 3L, "tester", "two"),
                () -> store.deactivate(APP, "p-x", 3L, "tester", "off"));
        for (Executable stale : staleWrites) {
            PreconditionFailedException refused = assertThrows(PreconditionFailedException.class, stale);

            assertEquals(5L, refused.toProblemDetail().currentRevision());
            assertArrayEquals(before, rawHead("p-x"));
        }
        assertThrows(StoredDocumentSchemaException.class, () -> store.findHead(APP, "p-x"));

        PolicyHead activated = store.activate(APP, "p-x", 2, 5L, "tester", "two");

        assertEquals(2, activated.activeVersion());
        assertEquals(6L, activated.revision());
    }

    /** Append conditions only the head's own marker too: it does not touch the copied content. */
    @Test
    void appendDoesNotDependOnTheCopiedContentsMarker() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", 1, policy("p-x", 1), 1L)
                        .append("schemaVersion", 1)
                        .append("activeContentSchemaVersion", UNKNOWN));
        collection("policy_versions").insertOne(unmarkedVersion("p-x", policy("p-x", 1)));

        assertEquals(2, store.append(APP, "p-x", policy("p-x", 2), 1L, "tester", "second"));

        Document stored = stored("policy_heads", Filters.eq("policyId", "p-x"));
        assertEquals(2L, stored.get("revision"));
        assertEquals(UNKNOWN, stored.get("activeContentSchemaVersion"));
    }

    // ── 6. A content marker lives only while its content does (ADR-034 §9) ──────

    @Test
    void deactivateLeavesNoContentMarkerBehind() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        store.create(APP, policy("p-d", 1), "tester", "first");
        store.activate(APP, "p-d", 1, 0L, "tester", "on");
        assertEquals(1, stored("policy_heads", Filters.eq("policyId", "p-d")).get("activeContentSchemaVersion"));

        store.deactivate(APP, "p-d", 1L, "tester", "off");

        Document stored = stored("policy_heads", Filters.eq("policyId", "p-d"));
        assertNull(stored.get("activeContent"));
        assertFalse(stored.containsKey("activeContentSchemaVersion"));
    }

    /** Deactivation conditions only the head's own marker, so an unknown content marker goes with its content. */
    @Test
    void deactivateRemovesAContentMarkerThisBuildDoesNotKnow() {
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", 1, policy("p-x", 1), 1L)
                        .append("schemaVersion", 1)
                        .append("activeContentSchemaVersion", UNKNOWN));

        store.deactivate(APP, "p-x", 1L, "tester", "off");

        assertFalse(stored("policy_heads", Filters.eq("policyId", "p-x")).containsKey("activeContentSchemaVersion"));
        assertNull(store.findHead(APP, "p-x").orElseThrow().activeVersion());
    }

    /**
     * A head stored with a content marker beside no content — as a deactivation wrote one before it
     * began removing the marker — reads, and is not rewritten to do so.
     */
    @Test
    void aContentMarkerBesideNoContentIsNotChecked() {
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", null, null, 1L)
                        .append("schemaVersion", 1)
                        .append("activeContentSchemaVersion", UNKNOWN));

        assertNull(store.findHead(APP, "p-x").orElseThrow().activeContent());
        assertEquals(
                UNKNOWN, stored("policy_heads", Filters.eq("policyId", "p-x")).get("activeContentSchemaVersion"));
    }

    // ── 7. The marker is an integer, whatever its width (ADR-034 §10, §11) ──────

    @Test
    void oneStoredAsInt64OrAsDoubleReadsAsShapeOne() {
        for (Object one : List.of(1L, 1.0d)) {
            wipe();
            collection("policy_heads")
                    .insertOne(unmarkedHead("p-x", null, null, 1L).append("schemaVersion", one));

            assertEquals(
                    "p-x",
                    store.findHead(APP, "p-x").orElseThrow().policyId(),
                    () -> "marker stored as " + one.getClass().getSimpleName());
        }
    }

    /** 2³²+1 has 1 in its low 32 bits: a narrowing conversion would read it as shape 1. */
    @Test
    void aValueWhoseLow32BitsAreOneIsRefused() {
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", null, null, 1L).append("schemaVersion", 4_294_967_297L));

        assertRefused(() -> store.findHead(APP, "p-x"));
    }

    @Test
    void twoStoredAsInt64IsRefused() {
        collection("policy_heads").insertOne(unmarkedHead("p-x", null, null, 1L).append("schemaVersion", 2L));

        assertThrows(StoredDocumentSchemaException.class, () -> store.findHead(APP, "p-x"));
    }

    /**
     * The write condition admits only the marker as an integer, 32- or 64-bit, or an absent one (ADR-034
     * §10), and never a value the read refuses (ADR-034 §11). Where the two differ it is the read that is
     * more tolerant: a floating-point or decimal {@code 1} still reads, and is not written to.
     *
     * <p>Each row is judged through every head writer, on a fresh head, and a refused write must leave the
     * stored head byte for byte as it was — and write no version either.
     */
    @Test
    void theWriteConditionAdmitsOnlyAnIntegerMarkerAndNothingTheReadRefuses() {
        Map<String, Executable> writers = new LinkedHashMap<>();
        writers.put("append", () -> store.append(APP, "p-x", policy("p-x", 2), 1L, "tester", "second"));
        writers.put("activate", () -> store.activate(APP, "p-x", 1, 1L, "tester", "one"));
        writers.put("deactivate", () -> store.deactivate(APP, "p-x", 1L, "tester", "off"));

        for (OwnMarker marker : OwnMarker.ROWS) {
            seedHeadWithOwnMarker(marker);
            Throwable read = outcomeOf(() -> store.findHead(APP, "p-x"));
            boolean readable = read == null;
            assertEquals(marker.readable(), readable, () -> marker.label() + ": read accepted? refused by " + read);

            for (Map.Entry<String, Executable> writer : writers.entrySet()) {
                String row = marker.label() + " / " + writer.getKey();
                seedHeadWithOwnMarker(marker);
                byte[] before = rawHead("p-x");

                Throwable outcome = outcomeOf(writer.getValue());
                boolean permitted = outcome == null;

                assertFalse(permitted && !readable, () -> row + ": a write was permitted that the read refuses");
                assertEquals(marker.writable(), permitted, () -> row + ": write permitted? refused by " + outcome);
                if (!permitted) {
                    assertArrayEquals(before, rawHead("p-x"), () -> row + ": a refused write changed the head");
                    assertEquals(
                            1, collection("policy_versions").countDocuments(), () -> row + ": a version was written");
                }
            }
        }
    }

    /** One stored value of a head's own marker, with whether the write and the read are meant to admit it. */
    private record OwnMarker(String label, Object stored, boolean writable, boolean readable) {

        private static final Object ABSENT = new Object();

        private static final List<OwnMarker> ROWS = List.of(
                new OwnMarker("int32 1", 1, true, true),
                new OwnMarker("int64 1", 1L, true, true),
                new OwnMarker("absent", ABSENT, true, true),
                // Read, not written: the codec converts them exactly, the write condition wants an integer.
                new OwnMarker("double 1.0", 1.0d, false, true),
                new OwnMarker("decimal128 1", new Decimal128(BigDecimal.ONE), false, true),
                // Equal to 1 for the store's comparison, but not for the codec's, which compares decimals by bits.
                new OwnMarker("decimal128 1.0", new Decimal128(new BigDecimal("1.0")), false, false),
                // Equality on an array field matches an array that contains the value.
                new OwnMarker("[1]", List.of(1), false, false),
                new OwnMarker("[2, 1]", List.of(2, 1), false, false),
                new OwnMarker("string \"1\"", "1", false, false),
                new OwnMarker("null", null, false, false),
                new OwnMarker("true", true, false, false),
                new OwnMarker("int32 2", 2, false, false),
                // 1 in its low 32 bits: a narrowing conversion would read it as shape 1.
                new OwnMarker("int64 4294967297", 4_294_967_297L, false, false));
    }

    /** A head with no content at revision 1, carrying {@code marker} as its own, over one known version. */
    private void seedHeadWithOwnMarker(OwnMarker marker) {
        wipe();
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        Document head = unmarkedHead("p-x", null, null, 1L);
        if (marker.stored() != OwnMarker.ABSENT) {
            head.append("schemaVersion", marker.stored());
        }
        collection("policy_heads").insertOne(head);
        collection("policy_versions").insertOne(unmarkedVersion("p-x", policy("p-x", 1)));
    }

    /** @return what {@code call} threw, or {@code null} if it returned. */
    private static Throwable outcomeOf(Executable call) {
        try {
            call.execute();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }

    // ── 8. The condition is IN the write, not a read before it (ADR-034 §8) ─────

    /*
     * A check-then-write behaves exactly like the conditioned write when nothing else is writing, so a
     * single-threaded test cannot tell them apart. These put the other writer in the gap, on the side that
     * does not depend on how the gap was opened: immediately before this build's write reaches the store,
     * another build rewrites the document in place into a shape this build does not know, without moving
     * its revision. Whatever was read before, and through whichever door, a condition inside the write
     * refuses; any decision taken before the write lands on the rewritten document.
     *
     * Each test asserts, in this order: that the other build did write — a test whose hook never fires
     * asserts nothing —, that the document is byte for byte what the other build left, and that the
     * caller was refused rather than answered success.
     */

    @Test
    void noAppendLandsOnAHeadThatChangedShapeJustBeforeTheWrite() {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        assertRefusedAfterAnotherBuildReshapesTheHead(
                () -> store.append(APP, "p-x", policy("p-x", 3), 1L, "tester", "third"));
        assertEquals(2, collection("policy_versions").countDocuments(), "append wrote a version");
    }

    @Test
    void noActivationLandsOnAHeadThatChangedShapeJustBeforeTheWrite() {
        assertRefusedAfterAnotherBuildReshapesTheHead(() -> store.activate(APP, "p-x", 2, 1L, "tester", "two"));
    }

    @Test
    void noDeactivationLandsOnAHeadThatChangedShapeJustBeforeTheWrite() {
        assertRefusedAfterAnotherBuildReshapesTheHead(() -> store.deactivate(APP, "p-x", 1L, "tester", "off"));
    }

    @Test
    void noConfigurationReplaceLandsOnADocumentThatChangedShapeJustBeforeTheWrite() {
        assertRefusedAfterAnotherBuildReshapesTheConfiguration(() -> configStore.replace(APP, draft(), 1L, "tester"));
    }

    /** The case that used to answer success while destroying a document this build cannot read. */
    @Test
    void noConfigurationDeleteLandsOnADocumentThatChangedShapeJustBeforeTheWrite() {
        assertRefusedAfterAnotherBuildReshapesTheConfiguration(() -> configStore.delete(APP, 1L));
    }

    @Test
    void noCatalogueReplaceLandsOnAnEntryThatChangedShapeJustBeforeTheWrite() {
        assertRefusedAfterAnotherBuildReshapesTheCatalogueEntry(
                () -> catalogueStore.replace(APP, "document", List.of("read", "approve"), 1L, "tester"));
    }

    /** The same case for the catalogue: a delete that answered success and destroyed the entry. */
    @Test
    void noCatalogueDeleteLandsOnAnEntryThatChangedShapeJustBeforeTheWrite() {
        assertRefusedAfterAnotherBuildReshapesTheCatalogueEntry(() -> catalogueStore.delete(APP, "document", 1L));
    }

    private void assertRefusedAfterAnotherBuildReshapesTheHead(Executable write) {
        collection("policy_heads")
                .insertOne(unmarkedHead("p-x", 1, policy("p-x", 1), 1L).append("schemaVersion", 1));
        collection("policy_versions").insertOne(unmarkedVersion("p-x", policy("p-x", 1)));
        collection("policy_versions").insertOne(unmarkedVersion("p-x", policy("p-x", 2)));
        OtherBuild otherBuild = new OtherBuild("policy_heads", Filters.eq("policyId", "p-x"));
        QuarkusMock.installMockForType(new HeadsAnotherBuildWritesFirst(otherBuild), PolicyHeadRepository.class);

        assertRefusedAfter(otherBuild, write);
    }

    private void assertRefusedAfterAnotherBuildReshapesTheConfiguration(Executable write) {
        collection("app_configs").insertOne(unmarkedConfig().append("schemaVersion", 1));
        OtherBuild otherBuild = new OtherBuild("app_configs", Filters.eq("app", APP));
        QuarkusMock.installMockForType(
                new ConfigurationsAnotherBuildWritesFirst(otherBuild), AppConfigRepository.class);

        assertRefusedAfter(otherBuild, write);
    }

    private void assertRefusedAfterAnotherBuildReshapesTheCatalogueEntry(Executable write) {
        collection("action_catalogue")
                .insertOne(unmarkedEntry("document", List.of("read")).append("schemaVersion", 1));
        OtherBuild otherBuild = new OtherBuild("action_catalogue", Filters.eq("resourceType", "document"));
        QuarkusMock.installMockForType(
                new CatalogueEntriesAnotherBuildWritesFirst(otherBuild), ActionCatalogueRepository.class);

        assertRefusedAfter(otherBuild, write);
    }

    private void assertRefusedAfter(OtherBuild otherBuild, Executable write) {
        Throwable outcome = outcomeOf(write);

        assertTrue(otherBuild.wrote(), "the other build never wrote, so the gap was never opened");
        assertArrayEquals(
                otherBuild.left(),
                raw(otherBuild.collection, otherBuild.document),
                "a write landed on the document after it changed shape");
        assertInstanceOf(
                StoredDocumentSchemaException.class,
                outcome,
                () -> "the caller must be refused, not answered " + (outcome == null ? "success" : outcome));
    }

    /**
     * Another build, rewriting one document in place into a shape this build does not know — its marker
     * only, not its revision — the first time this build sends a write to that document's collection.
     */
    private final class OtherBuild {

        private static final Set<String> WRITES = Set.of(
                "updateOne",
                "updateMany",
                "replaceOne",
                "deleteOne",
                "deleteMany",
                "findOneAndUpdate",
                "findOneAndReplace",
                "findOneAndDelete",
                "bulkWrite");

        private final String collection;
        private final Bson document;
        private byte[] left;

        OtherBuild(String collection, Bson document) {
            this.collection = collection;
            this.document = Filters.and(Filters.eq("app", APP), document);
        }

        /** @return {@code target}, except that the first write sent through it lets this build write first. */
        @SuppressWarnings("unchecked")
        <T> MongoCollection<T> before(MongoCollection<T> target) {
            return (MongoCollection<T>) Proxy.newProxyInstance(
                    MongoCollection.class.getClassLoader(),
                    new Class<?>[] {MongoCollection.class},
                    (proxy, method, args) -> {
                        if (left == null && WRITES.contains(method.getName())) {
                            collection(collection).updateOne(document, Updates.set("schemaVersion", UNKNOWN));
                            left = raw(collection, document);
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        boolean wrote() {
            return left != null;
        }

        byte[] left() {
            return left;
        }
    }

    /*
     * The three repositories, except that their collection lets another build write first. Installed per
     * test with QuarkusMock; vetoed so none is ever discovered as a second bean.
     */

    @Vetoed
    static final class HeadsAnotherBuildWritesFirst extends PolicyHeadRepository {

        private final OtherBuild otherBuild;

        HeadsAnotherBuildWritesFirst(OtherBuild otherBuild) {
            this.otherBuild = otherBuild;
        }

        @Override
        public MongoCollection<PolicyHeadDocument> mongoCollection() {
            return otherBuild.before(super.mongoCollection());
        }
    }

    @Vetoed
    static final class ConfigurationsAnotherBuildWritesFirst extends AppConfigRepository {

        private final OtherBuild otherBuild;

        ConfigurationsAnotherBuildWritesFirst(OtherBuild otherBuild) {
            this.otherBuild = otherBuild;
        }

        @Override
        public MongoCollection<AppConfigDocument> mongoCollection() {
            return otherBuild.before(super.mongoCollection());
        }
    }

    @Vetoed
    static final class CatalogueEntriesAnotherBuildWritesFirst extends ActionCatalogueRepository {

        private final OtherBuild otherBuild;

        CatalogueEntriesAnotherBuildWritesFirst(OtherBuild otherBuild) {
            this.otherBuild = otherBuild;
        }

        @Override
        public MongoCollection<ActionCatalogueDocument> mongoCollection() {
            return otherBuild.before(super.mongoCollection());
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /** A head of an unknown shape, with content and a revision, over two versions of a known one. */
    private void seedUnknownShapeHead(String policyId) {
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        collection("policy_heads")
                .insertOne(unmarkedHead(policyId, 1, policy(policyId, 1), 1L)
                        .append("schemaVersion", UNKNOWN)
                        .append("activeContentSchemaVersion", 1));
        collection("policy_versions").insertOne(unmarkedVersion(policyId, policy(policyId, 1)));
        collection("policy_versions").insertOne(unmarkedVersion(policyId, policy(policyId, 2)));
    }

    /** The head exactly as stored: its BSON bytes. */
    private byte[] rawHead(String policyId) {
        return raw("policy_heads", Filters.and(Filters.eq("app", APP), Filters.eq("policyId", policyId)));
    }

    /** A document exactly as stored: its BSON bytes. */
    private byte[] raw(String collection, Bson filter) {
        RawBsonDocument raw = headRepository
                .mongoDatabase()
                .getCollection(collection, RawBsonDocument.class)
                .find(filter)
                .first();
        assertTrue(raw != null, "no document in " + collection + " matching " + filter);
        ByteBuf buffer = raw.getByteBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Refused, by either of the two ways a stored marker can fail to be read as a known shape: the guard,
     * for an integer this build does not know, or the codec, for a value that is not an {@code int} at all
     * (ADR-034 §10). Both fail closed; what must not happen is a successful read.
     */
    private static void assertRefused(Executable read) {
        RuntimeException refused = assertThrows(RuntimeException.class, read);
        assertTrue(
                refused instanceof StoredDocumentSchemaException || refused instanceof CodecConfigurationException,
                () -> "refused by an unexpected failure: " + refused);
    }

    private MongoCollection<Document> collection(String name) {
        return headRepository.mongoDatabase().getCollection(name);
    }

    private Document stored(String collection, Bson filter) {
        Document found = collection(collection)
                .find(Filters.and(Filters.eq("app", APP), filter))
                .first();
        assertTrue(found != null, "no document in " + collection + " matching " + filter);
        return found;
    }

    private void dropContentMarker(String policyId) {
        collection("policy_heads")
                .updateOne(
                        Filters.and(Filters.eq("app", APP), Filters.eq("policyId", policyId)),
                        Updates.unset("activeContentSchemaVersion"));
        assertFalse(stored("policy_heads", Filters.eq("policyId", policyId)).containsKey("activeContentSchemaVersion"));
    }

    /** A head as stored before the marker existed. */
    private Document unmarkedHead(String policyId, Integer activeVersion, Policy activeContent, long revision) {
        return new Document("policyId", policyId)
                .append("app", APP)
                .append("resourceType", "document")
                .append("activeVersion", activeVersion)
                .append(
                        "activeContent",
                        activeContent == null ? null : new Document(contentMapper.toDocument(activeContent)))
                .append("revision", revision)
                .append("audit", audit());
    }

    /** A version as stored before the marker existed. */
    private Document unmarkedVersion(String policyId, Policy content) {
        return new Document("app", APP)
                .append("policyId", policyId)
                .append("version", content.version())
                .append("content", new Document(contentMapper.toDocument(content)))
                .append("audit", audit());
    }

    /** A configuration as stored before the marker existed. */
    private Document unmarkedConfig() {
        return new Document("app", APP)
                .append("subjectAttributes", new Document("rol", "realm_access.roles"))
                .append("pip", null)
                .append("revision", 1L)
                .append("audit", audit());
    }

    /** A catalogue entry as stored before the marker existed. */
    private Document unmarkedEntry(String resourceType, List<String> actions) {
        return new Document("app", APP)
                .append("resourceType", resourceType)
                .append("actions", actions)
                .append("revision", 1L)
                .append("audit", audit());
    }

    private static Document audit() {
        return new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
    }

    private static AppConfigDraft draft() {
        return new AppConfigDraft(
                Map.of("rol", "realm_access.roles"),
                new AppConfigDraft.PipDraft("https://backend/subjects/{sub}", 500, 300, "cred-ref"));
    }

    private static Policy withActions(Policy policy, List<String> actions) {
        return new Policy(
                policy.id(),
                policy.version(),
                policy.resourceType(),
                actions,
                policy.combiningAlgorithm(),
                policy.defaultEffect(),
                policy.rules());
    }

    private static Policy policy(String id, int version) {
        return new Policy(
                id,
                version,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "assigned-access",
                        Effect.PERMIT,
                        new Comparison(
                                Operator.IN,
                                new AttributeRef("subject.id"),
                                new AttributeRef("resource.attr.assignees")))));
    }
}
