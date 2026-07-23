package io.github.ricardoqmd.servicepolicy.enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.enumeration.EnumerationEvaluator.PolicyOutcome;
import io.github.ricardoqmd.servicepolicy.enumeration.EnumerationEvaluator.PolicyVerdict;

/**
 * The ADR-030 §B3 truth tables, exercised directly against the pure combining of
 * {@link EnumerationEvaluator} (no catalogue, no store): within a policy (rows 1–5) and across
 * policies (rows 1–4). This mirrors {@code PolicyEngine}'s two levels under Kleene logic; a drift in
 * either level would surface here rather than as a wrong hint in production.
 */
class EnumerationEvaluatorTest {

    private static final String TYPE = "document";
    private static final String ACTION = "update";
    private static final EnumerationContext CONTEXT = new EnumerationContext("alice", Map.of(), TYPE);

    // ── Within a policy (B3 policy level, rows 1–5) ──────────────────────────

    @Test
    void row1_matchedDenyRuleIsDeterministicDeny() {
        PolicyOutcome outcome = EnumerationEvaluator.evaluatePolicy(
                policy(Effect.PERMIT, rule(Effect.DENY, alwaysTrue()), rule(Effect.PERMIT, alwaysTrue())), CONTEXT);

        assertEquals(PolicyVerdict.DENY, outcome.verdict());
        assertTrue(outcome.refs().isEmpty());
    }

    @Test
    void row2_indeterminateDenyIsIndeterminateWithUnionOfAllIndeterminateRules() {
        PolicyOutcome outcome = EnumerationEvaluator.evaluatePolicy(
                policy(Effect.DENY, rule(Effect.DENY, dependsOn("areaId")), rule(Effect.PERMIT, dependsOn("owner"))),
                CONTEXT);

        assertEquals(PolicyVerdict.INDETERMINATE, outcome.verdict());
        assertEquals(Set.of("areaId", "owner"), outcome.refs());
    }

    @Test
    void row3_permitTrueWithNoDenyIsDeterministicPermit() {
        PolicyOutcome outcome = EnumerationEvaluator.evaluatePolicy(
                policy(Effect.DENY, rule(Effect.PERMIT, alwaysTrue()), rule(Effect.DENY, alwaysFalse())), CONTEXT);

        assertEquals(PolicyVerdict.PERMIT, outcome.verdict());
        assertTrue(outcome.refs().isEmpty());
    }

    @Test
    void row4_permitIndeterminateWithDefaultPermitIsDeterministicPermit() {
        // Rule fires → permit; rule fails → default permit. Permitted under every completion.
        PolicyOutcome outcome = EnumerationEvaluator.evaluatePolicy(
                policy(Effect.PERMIT, rule(Effect.PERMIT, dependsOn("areaId"))), CONTEXT);

        assertEquals(PolicyVerdict.PERMIT, outcome.verdict());
        assertTrue(outcome.refs().isEmpty());
    }

    @Test
    void row4_permitIndeterminateWithDefaultDenyIsIndeterminate() {
        PolicyOutcome outcome = EnumerationEvaluator.evaluatePolicy(
                policy(Effect.DENY, rule(Effect.PERMIT, dependsOn("areaId"))), CONTEXT);

        assertEquals(PolicyVerdict.INDETERMINATE, outcome.verdict());
        assertEquals(Set.of("areaId"), outcome.refs());
    }

    @Test
    void row5_allRulesFalseFallsThroughToDefaultEffectDeterministically() {
        assertEquals(
                PolicyVerdict.DENY,
                EnumerationEvaluator.evaluatePolicy(policy(Effect.DENY, rule(Effect.PERMIT, alwaysFalse())), CONTEXT)
                        .verdict());
        assertEquals(
                PolicyVerdict.PERMIT,
                EnumerationEvaluator.evaluatePolicy(policy(Effect.PERMIT, rule(Effect.DENY, alwaysFalse())), CONTEXT)
                        .verdict());
    }

    // ── Across policies (B3 cross-policy, rows 1–4) ──────────────────────────

    @Test
    void crossRow1_deterministicDenyOmitsThePairEvenAgainstAnIndeterminatePermit() {
        Policy denies = policy(Effect.DENY, rule(Effect.DENY, alwaysTrue())); // deterministic DENY
        Policy indeterminate = policy(Effect.DENY, rule(Effect.PERMIT, dependsOn("areaId"))); // INDETERMINATE

        Optional<EnumeratedPair> pair =
                EnumerationEvaluator.enumeratePair(List.of(indeterminate, denies), CONTEXT, ACTION);

        assertTrue(pair.isEmpty(), "a deterministic deny beats an indeterminate permit — pair omitted");
    }

    @Test
    void crossRow1_anApplicablePolicyThatDefaultDeniesOmitsThePair() {
        // Applies to (document, update), all rules false, defaultEffect DENY → deterministic DENY.
        Policy defaultDeny = policy(Effect.DENY, rule(Effect.PERMIT, alwaysFalse()));

        assertTrue(EnumerationEvaluator.enumeratePair(List.of(defaultDeny), CONTEXT, ACTION)
                .isEmpty());
    }

    @Test
    void crossRow2_indeterminateWithNoDenyIsConditionalWithUnionDependsOn() {
        Policy p1 = policy(Effect.DENY, rule(Effect.PERMIT, dependsOn("areaId")));
        Policy p2 = policy(Effect.DENY, rule(Effect.PERMIT, dependsOn("clasificacion")));

        EnumeratedPair pair = EnumerationEvaluator.enumeratePair(List.of(p1, p2), CONTEXT, ACTION)
                .orElseThrow();

        assertTrue(pair.conditional());
        assertEquals(List.of("areaId", "clasificacion"), pair.dependsOn()); // sorted union
        assertEquals(TYPE, pair.resourceType());
        assertEquals(ACTION, pair.action());
    }

    @Test
    void crossRow3_deterministicPermitIsUnconditionalWithNoDependsOn() {
        Policy permits = policy(Effect.DENY, rule(Effect.PERMIT, alwaysTrue()));

        EnumeratedPair pair = EnumerationEvaluator.enumeratePair(List.of(permits), CONTEXT, ACTION)
                .orElseThrow();

        assertFalse(pair.conditional());
        assertTrue(pair.dependsOn().isEmpty());
    }

    @Test
    void crossRow4_noApplicablePolicyOmitsThePair() {
        // The one policy does not govern this action (actions=["read"]), so nothing applies.
        Policy readOnly = new Policy(
                "read-only",
                1,
                TYPE,
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(rule(Effect.PERMIT, alwaysTrue())));

        assertTrue(EnumerationEvaluator.enumeratePair(List.of(readOnly), CONTEXT, ACTION)
                .isEmpty());
        assertTrue(
                EnumerationEvaluator.enumeratePair(List.of(), CONTEXT, ACTION).isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A policy governing (document, update) with the given default effect and rules. */
    private static Policy policy(Effect defaultEffect, Rule... rules) {
        return new Policy(
                "p", 1, TYPE, List.of(ACTION), CombiningAlgorithm.DENY_OVERRIDES, defaultEffect, List.of(rules));
    }

    private static Rule rule(Effect effect, Condition condition) {
        return new Rule("r-" + effect, effect, condition);
    }

    private static Condition alwaysTrue() {
        return new Comparison(Operator.EQ, new Literal(1), new Literal(1));
    }

    private static Condition alwaysFalse() {
        return new Comparison(Operator.EQ, new Literal(1), new Literal(2));
    }

    /** A comparison against a resource attribute — always INDETERMINATE, collecting {@code name}. */
    private static Condition dependsOn(String name) {
        return new Comparison(Operator.EQ, new AttributeRef("resource.attr." + name), new Literal("x"));
    }
}
