package io.github.ricardoqmd.servicepolicy.enumeration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.ricardoqmd.servicepolicy.domain.policy.And;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.ComparisonSemantics;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operand;
import io.github.ricardoqmd.servicepolicy.domain.policy.Or;

/**
 * Evaluates a {@link Condition} tree three-valued, with no resource instance (ADR-030 §4).
 *
 * <p>It walks the same AST as the enforcement {@code ConditionEvaluator}, but where that one resolves
 * an absent attribute to {@code null} and lets it deny, this one resolves an operand it cannot know
 * without an instance to {@code INDETERMINATE} and propagates it under Kleene three-valued logic.
 * When — and only when — both operands of a comparison resolve to concrete values, it defers to the
 * shared {@link ComparisonSemantics}, so a satisfied comparison means exactly here what it means at
 * enforcement. The two evaluators disagree about whether a value is known; they never disagree about
 * what a known value means.
 *
 * <p><strong>Resolution table (ADR-030 §B2).</strong> A literal is its value; {@code subject.id} is
 * the token subject; {@code subject.attr.K} is the derived value when present and INDETERMINATE when
 * absent (the caller may still assert it at {@code /evaluate}, ADR-010); {@code resource.type} is the
 * pair's type; {@code resource.id}, {@code context.K} and any {@code subject.attr}/{@code
 * resource.attr} the instance would carry are INDETERMINATE. Only {@code resource.attr.K} contributes
 * its name {@code K} as a {@code dependsOn} candidate.
 *
 * <p><strong>Divergence from enforcement on a malformed path.</strong> The enforcement evaluator
 * throws {@link io.github.ricardoqmd.servicepolicy.domain.exception.UnknownAttributeException} for a
 * path whose root it does not recognise — the fail-loud posture of a decision that must not guess.
 * Attribute paths are <em>not</em> validated at authoring: they are open bags, so an unknown root is
 * caught only at evaluation. This advisory path must never surface a 500 to a UI client, so it maps
 * the same unknown or malformed path to INDETERMINATE instead of throwing, and — being neither a
 * subject nor a resource attribute — it contributes no {@code dependsOn} name.
 */
// @Singleton would be fine, but the class is a stateless pure helper; the evaluator holds one.
public final class TriStateConditionEvaluator {

    private static final String SUBJECT_ATTR_PREFIX = "subject.attr.";
    private static final String RESOURCE_ATTR_PREFIX = "resource.attr.";
    private static final String CONTEXT_PREFIX = "context.";

    /** @return the three-valued result of the condition, carrying the refs that caused any indeterminacy. */
    public ConditionResult evaluate(Condition condition, EnumerationContext context) {
        return switch (condition) {
            case Comparison comparison -> compare(comparison, context);
            case And and -> and(and.conditions(), context);
            case Or or -> or(or.conditions(), context);
        };
    }

    private ConditionResult compare(Comparison cmp, EnumerationContext context) {
        OperandResolution left = resolve(cmp.left(), context);
        OperandResolution right = resolve(cmp.right(), context);

        if (left.indeterminate() || right.indeterminate()) {
            Set<String> refs = new LinkedHashSet<>();
            left.ref().ifPresent(refs::add);
            right.ref().ifPresent(refs::add);
            return ConditionResult.indeterminate(refs);
        }
        return ConditionResult.of(ComparisonSemantics.holds(cmp.op(), left.value(), right.value()));
    }

    /**
     * AND under Kleene: a single {@code FALSE} child settles the result to {@code FALSE} — no
     * completion of the instance can revive it, so it contributes no refs. Otherwise any
     * {@code INDETERMINATE} child leaves the whole undetermined, and the refs are the union over the
     * indeterminate children only. All children {@code TRUE} → {@code TRUE}.
     */
    private ConditionResult and(List<Condition> conditions, EnumerationContext context) {
        List<ConditionResult> results =
                conditions.stream().map(c -> evaluate(c, context)).toList();
        if (results.stream().anyMatch(r -> r.value() == TriState.FALSE)) {
            return ConditionResult.settled(TriState.FALSE);
        }
        return combineIndeterminateOrElse(results, TriState.TRUE);
    }

    /**
     * OR under Kleene: a single {@code TRUE} child settles the result to {@code TRUE}, contributing no
     * refs — the ADR-030 flagship case, where a satisfied type-level branch of an {@code OR} makes the
     * pair unconditional even though a sibling branch reads a resource attribute. Otherwise any
     * {@code INDETERMINATE} child leaves it undetermined, refs the union over the indeterminate
     * children. All children {@code FALSE} → {@code FALSE}.
     */
    private ConditionResult or(List<Condition> conditions, EnumerationContext context) {
        List<ConditionResult> results =
                conditions.stream().map(c -> evaluate(c, context)).toList();
        if (results.stream().anyMatch(r -> r.value() == TriState.TRUE)) {
            return ConditionResult.settled(TriState.TRUE);
        }
        return combineIndeterminateOrElse(results, TriState.FALSE);
    }

    /** Shared tail of AND/OR once the settling value is ruled out: indeterminate-with-union, else the given settled value. */
    private static ConditionResult combineIndeterminateOrElse(
            List<ConditionResult> results, TriState whenNoneIndeterminate) {
        Set<String> refs = new LinkedHashSet<>();
        boolean anyIndeterminate = false;
        for (ConditionResult result : results) {
            if (result.isIndeterminate()) {
                anyIndeterminate = true;
                refs.addAll(result.contributingResourceAttrs());
            }
        }
        return anyIndeterminate ? ConditionResult.indeterminate(refs) : ConditionResult.settled(whenNoneIndeterminate);
    }

    private OperandResolution resolve(Operand operand, EnumerationContext context) {
        return switch (operand) {
            case Literal literal -> OperandResolution.resolved(literal.value());
            case AttributeRef ref -> resolvePath(ref.path(), context);
        };
    }

    private OperandResolution resolvePath(String path, EnumerationContext context) {
        if (path.startsWith(SUBJECT_ATTR_PREFIX)) {
            String key = path.substring(SUBJECT_ATTR_PREFIX.length());
            // Present in the derived map → its value; absent → the caller might still assert it at
            // /evaluate (ADR-010), so it is undetermined here and NOT a resource-attr dependsOn name.
            return context.subjectAttributes().containsKey(key)
                    ? OperandResolution.resolved(context.subjectAttributes().get(key))
                    : OperandResolution.indeterminate(Optional.empty());
        }
        if (path.startsWith(RESOURCE_ATTR_PREFIX)) {
            // The one collectable source of indeterminacy: the client can supply this at /evaluate.
            return OperandResolution.indeterminate(Optional.of(path.substring(RESOURCE_ATTR_PREFIX.length())));
        }
        if (path.startsWith(CONTEXT_PREFIX)) {
            return OperandResolution.indeterminate(Optional.empty());
        }
        return switch (path) {
            case "subject.id" -> OperandResolution.resolved(context.subjectId());
            case "resource.type" -> OperandResolution.resolved(context.resourceType());
            case "resource.id" -> OperandResolution.indeterminate(Optional.empty());
            // Unknown/malformed: INDETERMINATE, not collected. Enforcement throws here (see javadoc).
            default -> OperandResolution.indeterminate(Optional.empty());
        };
    }

    /**
     * One operand's resolution: a concrete {@code value} (possibly {@code null}, for a null literal),
     * or {@code indeterminate}, in which case {@code ref} carries the {@code resource.attr} name to
     * collect — present only for a {@code resource.attr.*} operand, empty for every other unresolved
     * source.
     */
    private record OperandResolution(boolean indeterminate, Object value, Optional<String> ref) {

        static OperandResolution resolved(Object value) {
            return new OperandResolution(false, value, Optional.empty());
        }

        static OperandResolution indeterminate(Optional<String> ref) {
            return new OperandResolution(true, null, ref);
        }
    }
}
