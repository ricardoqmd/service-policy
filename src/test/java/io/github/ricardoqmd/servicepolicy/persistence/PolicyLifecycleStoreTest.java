package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.HeadStatus;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Read-path tests for {@link PolicyLifecycleStore} over the head-pointer model (ADR-016). Seeds
 * {@code policy_heads} and {@code policy_versions} documents directly (writes land in later slices)
 * and asserts pagination, ordering, active-only filtering, and single lookups against Dev Services
 * MongoDB. Every identity lookup is keyed by the composite {@code (app, policyId)} of ADR-026.
 */
@QuarkusTest
class PolicyLifecycleStoreTest {

    private static final String APP = "test-app";
    private static final String OTHER_APP = "other-app";

    @Inject
    PolicyLifecycleStore store;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    private final PolicyDocumentMapper contentMapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    @BeforeEach
    void clean() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
    }

    @Test
    void findsActiveHeadsOrderedByPolicyIdAndPaged() {
        seedHead(APP, "p-b", 2, 3, policy("p-b", 2));
        seedHead(APP, "p-a", 1, 1, policy("p-a", 1));

        assertEquals(2, store.countHeads(null, HeadStatus.ACTIVE));

        List<PolicyHead> firstPage = store.findHeads(null, HeadStatus.ACTIVE, 0, 1);
        assertEquals(1, firstPage.size());
        assertEquals("p-a", firstPage.get(0).policyId()); // ascending by policyId
        assertEquals(1, firstPage.get(0).activeVersion());
        assertEquals("document", firstPage.get(0).resourceType());
        assertNotNull(firstPage.get(0).activeContent());
        assertEquals("u-1", firstPage.get(0).audit().createdBy());

        List<PolicyHead> secondPage = store.findHeads(null, HeadStatus.ACTIVE, 1, 1);
        assertEquals(1, secondPage.size());
        assertEquals("p-b", secondPage.get(0).policyId());
    }

    @Test
    void excludesInactiveHeadsFromActiveListButFindHeadReturnsThem() {
        seedHead(APP, "draft", null, 0, null);

        assertEquals(0, store.countHeads(null, HeadStatus.ACTIVE));
        assertTrue(store.findHeads(null, HeadStatus.ACTIVE, 0, 20).isEmpty());

        Optional<PolicyHead> draft = store.findHead(APP, "draft");
        assertTrue(draft.isPresent());
        assertNull(draft.get().activeVersion());
        assertNull(draft.get().activeContent());
    }

    /** ADR-025: the listing store sees every lifecycle state; 'all' applies no state filter. */
    @Test
    void findsHeadsInEveryLifecycleStateWhenStatusIsAll() {
        seedHead(APP, "p-active", 1, 1, policy("p-active", 1));
        seedHead(APP, "p-draft", null, 0, null);

        assertEquals(2, store.countHeads(null, HeadStatus.ALL));

        List<PolicyHead> heads = store.findHeads(null, HeadStatus.ALL, 0, 20);
        assertEquals(
                List.of("p-active", "p-draft"),
                heads.stream().map(PolicyHead::policyId).toList());
        assertNull(heads.get(1).activeVersion());
    }

    @Test
    void findsOnlyInactiveHeadsWhenStatusIsInactive() {
        seedHead(APP, "p-active", 1, 1, policy("p-active", 1));
        seedHead(APP, "p-draft", null, 0, null);

        assertEquals(1, store.countHeads(null, HeadStatus.INACTIVE));

        List<PolicyHead> heads = store.findHeads(null, HeadStatus.INACTIVE, 0, 20);
        assertEquals(1, heads.size());
        assertEquals("p-draft", heads.get(0).policyId());
    }

    @Test
    void findsHeadById() {
        seedHead(APP, "p-a", 1, 5, policy("p-a", 1));

        Optional<PolicyHead> head = store.findHead(APP, "p-a");
        assertTrue(head.isPresent());
        assertEquals(5, head.get().revision());
        assertEquals(1, head.get().activeVersion());
        assertEquals("seed", head.get().audit().changeReason());
    }

    @Test
    void findHeadIsEmptyForUnknownPolicy() {
        assertTrue(store.findHead(APP, "nope").isEmpty());
    }

    /** ADR-026: identity is (app, policyId); a policy of one app is invisible under another. */
    @Test
    void findHeadIsEmptyWhenThePolicyBelongsToAnotherApp() {
        seedHead(APP, "p-a", 1, 1, policy("p-a", 1));

        assertTrue(store.findHead(OTHER_APP, "p-a").isEmpty());
        assertFalse(store.headExists(OTHER_APP, "p-a"));
    }

    @Test
    void headExistsReflectsPresence() {
        assertFalse(store.headExists(APP, "p-a"));
        seedHead(APP, "p-a", 1, 1, policy("p-a", 1));
        assertTrue(store.headExists(APP, "p-a"));
    }

    @Test
    void findsVersionsNewestFirstAndPaged() {
        seedVersion(APP, "p-a", 1);
        seedVersion(APP, "p-a", 2);
        seedVersion(APP, "p-a", 3);

        assertEquals(3, store.countVersions(APP, "p-a"));

        List<PolicyVersion> page = store.findVersions(APP, "p-a", 0, 2);
        assertEquals(2, page.size());
        assertEquals(3, page.get(0).content().version()); // descending by version
        assertEquals(2, page.get(1).content().version());
        assertEquals(APP, page.get(0).app());
        assertEquals("v3", page.get(0).audit().changeReason());
    }

    @Test
    void findsSpecificVersion() {
        seedVersion(APP, "p-a", 1);
        seedVersion(APP, "p-a", 2);

        Optional<PolicyVersion> version = store.findVersion(APP, "p-a", 1);
        assertTrue(version.isPresent());
        assertEquals(APP, version.get().app());
        assertEquals(1, version.get().content().version());
        assertEquals("document", version.get().content().resourceType());
    }

    @Test
    void findVersionIsEmptyWhenMissing() {
        seedVersion(APP, "p-a", 1);
        assertTrue(store.findVersion(APP, "p-a", 99).isEmpty());
        assertTrue(store.findVersion(APP, "other", 1).isEmpty());
        assertTrue(store.findVersion(OTHER_APP, "p-a", 1).isEmpty());
    }

    // --- ADR-026: composite (app, policyId) identity at the store level -------

    @Test
    void samePolicyIdInTwoAppsYieldsTwoIndependentHeads() {
        store.create(APP, policy("shared", 1), "tester", "in test-app");
        store.create(OTHER_APP, policy("shared", 1), "tester", "in other-app");

        assertTrue(store.headExists(APP, "shared"));
        assertTrue(store.headExists(OTHER_APP, "shared"));

        PolicyHead inApp = store.findHead(APP, "shared").orElseThrow();
        PolicyHead inOtherApp = store.findHead(OTHER_APP, "shared").orElseThrow();
        assertEquals(APP, inApp.app());
        assertEquals(OTHER_APP, inOtherApp.app());

        // Each app keeps its own version history under the same policyId.
        assertEquals(1, store.countVersions(APP, "shared"));
        assertEquals(1, store.countVersions(OTHER_APP, "shared"));

        // Activating one leaves the other untouched.
        store.activate(APP, "shared", 1, inApp.revision(), "tester", "go live");

        assertEquals(1, store.findHead(APP, "shared").orElseThrow().activeVersion());
        PolicyHead untouched = store.findHead(OTHER_APP, "shared").orElseThrow();
        assertNull(untouched.activeVersion());
        assertNull(untouched.activeContent());
    }

    // --- ADR-020 §4: activeContent write-path behavioral invariants ----------

    @Test
    void createLeavesActiveVersionAndActiveContentNull() {
        store.create(APP, policy("p-inv", 1), "tester", "invariant test");

        PolicyHeadDocument head =
                headRepository.findByAppAndPolicyId(APP, "p-inv").orElseThrow();
        assertNull(head.activeVersion);
        assertNull(head.activeContent);
    }

    @Test
    void appendDoesNotChangeActiveVersionOrActiveContent() {
        store.create(APP, policy("p-inv", 1), "tester", null);

        PolicyHeadDocument headAfterCreate =
                headRepository.findByAppAndPolicyId(APP, "p-inv").orElseThrow();
        long revision = headAfterCreate.revision;

        store.append(APP, "p-inv", policy("p-inv", 2), revision, "tester", null);

        PolicyHeadDocument headAfterAppend =
                headRepository.findByAppAndPolicyId(APP, "p-inv").orElseThrow();
        assertNull(headAfterAppend.activeVersion);
        assertNull(headAfterAppend.activeContent);
    }

    @Test
    void activateSetsActiveVersionAndActiveContentVerbatim() {
        store.create(APP, policy("p-inv", 1), "tester", null);

        long revision = headRepository.findByAppAndPolicyId(APP, "p-inv").orElseThrow().revision;
        PolicyHead head = store.activate(APP, "p-inv", 1, revision, "tester", "go live");

        assertEquals(1, head.activeVersion());
        assertNotNull(head.activeContent());

        // Verbatim check: activeContent in the head document must be the exact same BSON
        // Document that was stored as version 1, with no domain round-trip in between.
        PolicyVersionDocument versionDoc = versionRepository
                .findByAppAndPolicyIdAndVersion(APP, "p-inv", 1)
                .orElseThrow();
        PolicyHeadDocument headDoc =
                headRepository.findByAppAndPolicyId(APP, "p-inv").orElseThrow();
        assertEquals(versionDoc.content, headDoc.activeContent);
    }

    @Test
    void deactivateClearsActiveVersionAndActiveContent() {
        store.create(APP, policy("p-inv", 1), "tester", null);
        long rev0 = headRepository.findByAppAndPolicyId(APP, "p-inv").orElseThrow().revision;

        PolicyHead activated = store.activate(APP, "p-inv", 1, rev0, "tester", null);
        PolicyHead deactivated = store.deactivate(APP, "p-inv", activated.revision(), "tester", "retiring");

        assertNull(deactivated.activeVersion());
        assertNull(deactivated.activeContent());
    }

    // --- seeding helpers -----------------------------------------------------

    private Policy policy(String id, int version) {
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

    private Document audit(String changeReason) {
        return new Document("createdBy", "u-1")
                .append("createdAt", "2026-07-01T00:00:00Z")
                .append("changeReason", changeReason);
    }

    private void seedHead(String app, String policyId, Integer activeVersion, long revision, Policy activeContent) {
        PolicyHeadDocument doc = new PolicyHeadDocument();
        doc.policyId = policyId;
        doc.app = app;
        doc.resourceType = "document";
        doc.activeVersion = activeVersion;
        doc.revision = revision;
        doc.audit = audit("seed");
        doc.activeContent = activeContent == null ? null : new Document(contentMapper.toDocument(activeContent));
        headRepository.persist(doc);
    }

    private void seedVersion(String app, String policyId, int version) {
        PolicyVersionDocument doc = new PolicyVersionDocument();
        doc.app = app;
        doc.policyId = policyId;
        doc.version = version;
        doc.content = new Document(contentMapper.toDocument(policy(policyId, version)));
        doc.audit = audit("v" + version);
        versionRepository.persist(doc);
    }
}
