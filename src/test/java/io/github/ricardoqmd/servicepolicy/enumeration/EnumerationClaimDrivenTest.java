package io.github.ricardoqmd.servicepolicy.enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.ActionCatalogueTestSupport;
import io.github.ricardoqmd.servicepolicy.domain.policy.And;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Or;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDocument;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigProvider;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The claim → attribute → outcome chain end to end below the HTTP boundary (ADR-030 §B5 + §3): a
 * mapped scalar claim and a mapped array-of-scalars claim each drive a <em>deterministic</em> pair,
 * and the same policies with no derived attributes leave those pairs <em>conditional</em>.
 *
 * <p>This is verified here rather than through {@code GET /permissions} because injecting token claims
 * needs a running OIDC identity, which the test profile disables. The deriver takes a token double,
 * and its output flows into the real {@link EnumerationEvaluator} over real seeded catalogue, policies
 * and configuration — the exact wiring the resource performs, minus the framework's token plumbing.
 */
@QuarkusTest
class EnumerationClaimDrivenTest {

    private static final String APP = "claim-driven-app";
    private static final String SUBJECT = "alice";

    @Inject
    SubjectAttributeDeriver deriver;

    @Inject
    EnumerationEvaluator evaluator;

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @Inject
    AppConfigRepository configRepository;

    @Inject
    AppConfigProvider configProvider;

    @BeforeEach
    @AfterEach
    void clean() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
        configRepository.deleteAll();
        configProvider.invalidate(APP);
    }

    @Test
    void mappedScalarAndArrayClaimsDriveDeterministicPairsWhileUnmappedLeaveThemConditional() {
        // Catalogue: 'share' gated on a scalar attribute, 'review' on an array attribute.
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "share", "review");
        configure(Map.of("area", "dept", "roles", "groups")); // ADR-029 claim mapping
        activate(policy(
                "p-share",
                List.of("share"),
                new Comparison(Operator.EQ, new AttributeRef("subject.attr.area"), new Literal("north"))));
        activate(policy(
                "p-review",
                List.of("review"),
                new Comparison(Operator.IN, new Literal("reviewer"), new AttributeRef("subject.attr.roles"))));

        // With claims present: dept=north (scalar) and groups=[reviewer] (array of scalars).
        Map<String, Object> derived =
                deriver.derive(APP, new FakeJsonWebToken(Map.of("dept", "north", "groups", List.of("reviewer"))));
        assertEquals("north", derived.get("area"));
        assertEquals(List.of("reviewer"), derived.get("roles"));

        assertFalse(conditionalOf("share", derived), "a resolved scalar attribute makes the pair deterministic");
        assertFalse(conditionalOf("review", derived), "a resolved array attribute makes the pair deterministic");

        // With no claims: both attributes unresolved → both pairs conditional (degradation), no dependsOn.
        Map<String, Object> none = deriver.derive(APP, new FakeJsonWebToken(Map.of()));
        assertTrue(none.isEmpty());
        assertTrue(conditionalOf("share", none));
        assertTrue(conditionalOf("review", none));
        assertTrue(pair("share", none).dependsOn().isEmpty(), "subject attributes are never dependsOn names");
    }

    /**
     * Drives every condition shape and operator through the real (JaCoCo-measured) enumeration path:
     * AND/OR settling and propagation, each resolvability arm of §B2, and the resolved-operand
     * operators. The pure {@code TriStateConditionEvaluatorTest} asserts these more exhaustively, but
     * runs outside the Quarkus agent, so this @QuarkusTest is what exercises the branches under
     * coverage. Attributes are supplied directly to {@code enumerate}: area=north, clearance=5,
     * roles=[reviewer].
     */
    @Test
    void everyConditionShapeIsExercisedThroughEnumeration() {
        Comparison areaNorth = new Comparison(Operator.EQ, new AttributeRef("subject.attr.area"), new Literal("north"));
        Comparison areaSouth = new Comparison(Operator.EQ, new AttributeRef("subject.attr.area"), new Literal("south"));
        Comparison resourceAttr =
                new Comparison(Operator.EQ, new AttributeRef("resource.attr.areaId"), new Literal("x"));
        Comparison subjIsBob = new Comparison(Operator.EQ, new AttributeRef("subject.id"), new Literal("bob"));

        Map<String, Condition> byAction = new java.util.LinkedHashMap<>();
        byAction.put("and-indet", new And(List.of(areaNorth, resourceAttr))); // T & I → conditional
        byAction.put("and-false", new And(List.of(areaSouth, resourceAttr))); // F & _ → omitted
        byAction.put("or-true", new Or(List.of(areaNorth, resourceAttr))); // T | I → deterministic
        byAction.put("or-indet", new Or(List.of(areaSouth, resourceAttr))); // F | I → conditional
        byAction.put("or-false", new Or(List.of(areaSouth, subjIsBob))); // F | F → omitted
        byAction.put(
                "notin",
                new Comparison(
                        Operator.NOT_IN,
                        new Literal("admin"),
                        new AttributeRef("subject.attr.roles"))); // T → deterministic
        byAction.put(
                "in",
                new Comparison(
                        Operator.IN,
                        new Literal("reviewer"),
                        new AttributeRef("subject.attr.roles"))); // T → deterministic
        byAction.put(
                "gt",
                new Comparison(
                        Operator.GT,
                        new AttributeRef("subject.attr.clearance"),
                        new Literal(3))); // 5>3 → deterministic
        byAction.put(
                "lt",
                new Comparison(
                        Operator.LT,
                        new AttributeRef("subject.attr.clearance"),
                        new Literal(3))); // 5<3 false → omitted
        byAction.put(
                "ctx",
                new Comparison(Operator.EQ, new AttributeRef("context.env"), new Literal("prod"))); // I → conditional
        byAction.put(
                "resid",
                new Comparison(Operator.EQ, new AttributeRef("resource.id"), new Literal("d1"))); // I → conditional
        byAction.put(
                "unknown",
                new Comparison(
                        Operator.EQ,
                        new AttributeRef("bogus.path"),
                        new Literal("y"))); // I, not collected → conditional
        byAction.put(
                "missing",
                new Comparison(
                        Operator.EQ,
                        new AttributeRef("subject.attr.nope"),
                        new Literal("y"))); // I, no ref → conditional

        catalogueRepository.delete("{'app': ?1, 'resourceType': ?2}", APP, "document");
        ActionCatalogueTestSupport.declare(
                catalogueRepository, APP, "document", byAction.keySet().toArray(String[]::new));
        byAction.forEach((action, condition) -> activate(new Policy(
                "p-" + action,
                1,
                "document",
                List.of(action),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule("permit", Effect.PERMIT, condition)))));

        Map<String, Object> attrs = Map.of("area", "north", "clearance", 5, "roles", List.of("reviewer"));
        Map<String, EnumeratedPair> pairs = new java.util.HashMap<>();
        evaluator.enumerate(APP, SUBJECT, attrs).forEach(p -> pairs.put(p.action(), p));

        // Deterministic permits (unconditional, listed):
        assertFalse(pairs.get("or-true").conditional());
        assertFalse(pairs.get("notin").conditional());
        assertFalse(pairs.get("in").conditional());
        assertFalse(pairs.get("gt").conditional());
        // Conditional (listed, true):
        assertTrue(pairs.get("and-indet").conditional());
        assertTrue(pairs.get("or-indet").conditional());
        assertTrue(pairs.get("ctx").conditional());
        assertTrue(pairs.get("resid").conditional());
        assertTrue(pairs.get("unknown").conditional());
        assertTrue(pairs.get("missing").conditional());
        assertEquals(List.of("areaId"), pairs.get("and-indet").dependsOn());
        // Omitted (deterministic deny / all-false → default deny):
        assertFalse(pairs.containsKey("and-false"));
        assertFalse(pairs.containsKey("or-false"));
        assertFalse(pairs.containsKey("lt"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean conditionalOf(String action, Map<String, Object> derived) {
        return pair(action, derived).conditional();
    }

    private EnumeratedPair pair(String action, Map<String, Object> derived) {
        Optional<EnumeratedPair> found = evaluator.enumerate(APP, SUBJECT, derived).stream()
                .filter(p -> p.action().equals(action))
                .findFirst();
        return found.orElseThrow(() -> new AssertionError("pair '" + action + "' was omitted"));
    }

    private void configure(Map<String, String> mapping) {
        AppConfigDocument document = new AppConfigDocument();
        document.app = APP;
        Document subjectAttributes = new Document();
        mapping.forEach(subjectAttributes::append);
        document.subjectAttributes = subjectAttributes;
        document.revision = 1L;
        document.audit = new Document("createdBy", "seed").append("createdAt", "2026-01-01T00:00:00Z");
        configRepository.persist(document);
        configProvider.invalidate(APP);
    }

    private void activate(Policy policy) {
        lifecycleStore.create(APP, policy, "seed", null);
        lifecycleStore.activate(APP, policy.id(), 1, 0L, "seed", null);
    }

    private static Policy policy(String id, List<String> actions, Comparison permitWhen) {
        return new Policy(
                id,
                1,
                "document",
                actions,
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule("permit", Effect.PERMIT, permitWhen)));
    }
}
