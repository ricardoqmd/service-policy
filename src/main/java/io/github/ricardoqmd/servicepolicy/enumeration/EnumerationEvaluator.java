package io.github.ricardoqmd.servicepolicy.enumeration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Singleton;

import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;

/**
 * Enumerates the {@code (resourceType, action)} pairs the caller can act on, three-valued and with no
 * resource instance (ADR-030). The isolated counterpart of {@code PolicyEngine}: it mirrors that
 * engine's two combining levels exactly — within a policy, then across policies — but under Kleene
 * logic, so the third outcome (INDETERMINATE → {@code conditional}) survives instead of collapsing to
 * deny.
 *
 * <p>For each catalogue entry (ADR-028) it loads the app's active policies for that resource type
 * once, then evaluates every action of the entry against the applicable subset
 * ({@link Policy#appliesTo} — literal membership, ADR-028). Deterministic-deny and no-applicable-policy
 * pairs are omitted; the rest are returned sorted by {@code (resourceType, action)}.
 *
 * <p>Mirroring enforcement is deliberate and load-bearing: it is what keeps enumeration honest.
 * A pair whose only applicable policy denies by default is omitted here for the same reason
 * {@code /evaluate} denies it — anything else would advertise a permission enforcement will refuse.
 *
 * <p>This bean lives in {@code enumeration} and depends only on {@code domain} and {@code persistence}
 * (never {@code rest}, never {@code evaluation}); the build enforces it. The {@code INDETERMINATE}
 * value is translated to a boolean {@code conditional} at the REST boundary and never reaches a
 * {@code Decision}.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class EnumerationEvaluator {

    // Stateless and pure; a single shared instance backs the static combining below.
    private static final TriStateConditionEvaluator CONDITION_EVALUATOR = new TriStateConditionEvaluator();

    private final ActionCatalogueStore catalogueStore;
    private final PolicyLifecycleStore lifecycleStore;

    EnumerationEvaluator(ActionCatalogueStore catalogueStore, PolicyLifecycleStore lifecycleStore) {
        this.catalogueStore = catalogueStore;
        this.lifecycleStore = lifecycleStore;
    }

    /**
     * @param subjectAttributes attributes derived for the subject (ADR-029), empty when the app has no
     *     configuration or no mapping — in which case every {@code subject.attr} reference is
     *     INDETERMINATE and its pairs lean conditional rather than being omitted (ADR-030 degradation).
     * @return the included pairs, sorted by {@code (resourceType, action)}. Empty when the app has no
     *     catalogue — nothing to enumerate — which is a valid answer, not an error.
     */
    public List<EnumeratedPair> enumerate(String app, String subjectId, Map<String, Object> subjectAttributes) {
        List<EnumeratedPair> pairs = new ArrayList<>();
        catalogueStore.list(app).forEach(entry -> {
            String resourceType = entry.resourceType();
            List<Policy> active = lifecycleStore.activePoliciesFor(app, resourceType);
            EnumerationContext context = new EnumerationContext(subjectId, subjectAttributes, resourceType);
            for (String action : entry.actions()) {
                enumeratePair(active, context, action).ifPresent(pairs::add);
            }
        });
        // enumeratePair / evaluatePolicy below are static and pure (no store access), so the ADR-030
        // §B3 truth tables can be exercised directly by a unit test without a database.
        pairs.sort(Comparator.comparing(EnumeratedPair::resourceType).thenComparing(EnumeratedPair::action));
        return pairs;
    }

    /**
     * Combines the applicable policies across the deny-overrides levels (ADR-030 §B3 cross-policy),
     * mirroring {@code PolicyEngine.evaluate}:
     *
     * <ol>
     *   <li>any policy deterministic DENY → the pair is <em>omitted</em> (a matched deny, or an
     *       all-false policy defaulting DENY, both of which enforcement would refuse today);
     *   <li>else any policy INDETERMINATE → {@code conditional}, {@code dependsOn} = union of the
     *       indeterminate policies' refs;
     *   <li>else any policy deterministic PERMIT → included, {@code conditional: false};
     *   <li>else (no applicable policy) → omitted, enforcement's fail-safe default.
     * </ol>
     */
    static Optional<EnumeratedPair> enumeratePair(List<Policy> active, EnumerationContext context, String action) {
        String resourceType = context.resourceType();
        List<PolicyOutcome> outcomes = active.stream()
                .filter(policy -> policy.appliesTo(resourceType, action))
                .map(policy -> evaluatePolicy(policy, context))
                .toList();

        if (outcomes.stream().anyMatch(o -> o.verdict() == PolicyVerdict.DENY)) {
            return Optional.empty();
        }

        List<PolicyOutcome> indeterminate = outcomes.stream()
                .filter(o -> o.verdict() == PolicyVerdict.INDETERMINATE)
                .toList();
        if (!indeterminate.isEmpty()) {
            Set<String> dependsOn = new LinkedHashSet<>();
            indeterminate.forEach(o -> dependsOn.addAll(o.refs()));
            return Optional.of(new EnumeratedPair(resourceType, action, true, sorted(dependsOn)));
        }

        if (outcomes.stream().anyMatch(o -> o.verdict() == PolicyVerdict.PERMIT)) {
            return Optional.of(new EnumeratedPair(resourceType, action, false, List.of()));
        }
        return Optional.empty();
    }

    /**
     * The within-a-policy combining (ADR-030 §B3 policy level), mirroring
     * {@code PolicyEngine.evaluatePolicy} with {@code defaultEffect}:
     *
     * <ol>
     *   <li>some DENY rule TRUE → DENY (a matched deny survives every completion);
     *   <li>else some DENY rule INDETERMINATE → INDETERMINATE (an unresolved deny may still fire);
     *   <li>else some PERMIT rule TRUE → PERMIT (no deny can now materialize);
     *   <li>else some PERMIT rule INDETERMINATE → PERMIT if {@code defaultEffect} is PERMIT (permitted
     *       under every completion: rule fires → permit, rule fails → default permit), else
     *       INDETERMINATE;
     *   <li>else (all rules FALSE) → {@code defaultEffect}, deterministic.
     * </ol>
     *
     * <p>Refs, when the policy is INDETERMINATE, are the union over every rule whose condition is
     * INDETERMINATE — the only rules that could still change the outcome.
     */
    static PolicyOutcome evaluatePolicy(Policy policy, EnumerationContext context) {
        boolean denyTrue = false;
        boolean denyIndeterminate = false;
        boolean permitTrue = false;
        boolean permitIndeterminate = false;
        Set<String> refs = new LinkedHashSet<>();

        for (Rule rule : policy.rules()) {
            ConditionResult result = CONDITION_EVALUATOR.evaluate(rule.condition(), context);
            if (result.isIndeterminate()) {
                refs.addAll(result.contributingResourceAttrs());
            }
            if (rule.effect() == Effect.DENY) {
                denyTrue |= result.value() == TriState.TRUE;
                denyIndeterminate |= result.isIndeterminate();
            } else {
                permitTrue |= result.value() == TriState.TRUE;
                permitIndeterminate |= result.isIndeterminate();
            }
        }

        if (denyTrue) {
            return PolicyOutcome.deterministic(PolicyVerdict.DENY);
        }
        if (denyIndeterminate) {
            return PolicyOutcome.indeterminate(refs);
        }
        if (permitTrue) {
            return PolicyOutcome.deterministic(PolicyVerdict.PERMIT);
        }
        if (permitIndeterminate) {
            return policy.defaultEffect() == Effect.PERMIT
                    ? PolicyOutcome.deterministic(PolicyVerdict.PERMIT)
                    : PolicyOutcome.indeterminate(refs);
        }
        return PolicyOutcome.deterministic(
                policy.defaultEffect() == Effect.PERMIT ? PolicyVerdict.PERMIT : PolicyVerdict.DENY);
    }

    private static List<String> sorted(Set<String> names) {
        return names.stream().sorted().toList();
    }

    /** The three verdicts a single policy can carry under three-valued combining. */
    enum PolicyVerdict {
        DENY,
        PERMIT,
        INDETERMINATE
    }

    /** A policy's verdict plus, when INDETERMINATE, the resource-attr refs that left it so. */
    record PolicyOutcome(PolicyVerdict verdict, Set<String> refs) {

        static PolicyOutcome deterministic(PolicyVerdict verdict) {
            return new PolicyOutcome(verdict, Set.of());
        }

        static PolicyOutcome indeterminate(Set<String> refs) {
            return new PolicyOutcome(PolicyVerdict.INDETERMINATE, Set.copyOf(refs));
        }
    }
}
