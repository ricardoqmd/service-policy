package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.Collection;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * The truth of the eight comparison operators, in one place.
 *
 * <p>Pure and total: given two already-resolved operand values (either may be {@code null}), it
 * says whether the comparison holds. It knows nothing about where the values came from — attribute
 * resolution is the caller's job — which is exactly why it can be shared.
 *
 * <p>It is shared by two evaluators with opposite postures toward the unknown. Enforcement
 * ({@link ConditionEvaluator}, ADR-011/ADR-023) resolves an absent attribute to {@code null} and
 * relies on the null-operand rules here to make the comparison fail closed. Enumeration
 * ({@code TriStateConditionEvaluator}, ADR-030) never even calls this method for an unresolved
 * operand — it yields {@code INDETERMINATE} upstream — and reaches here only when both operands are
 * in hand. The two postures diverge on <em>whether</em> a value is known; they must not diverge on
 * what a known value <em>means</em>. Housing the operator truth once is what guarantees that: there
 * is no second copy to drift.
 *
 * <p>The rules preserved verbatim from the enforcement path: a {@code null} operand makes any
 * comparison not hold (ADR-011); an ordering operator over a non-numeric operand does not hold
 * rather than raising (ADR-023); {@code NOT_IN} against a present non-collection right is vacuously
 * true (a type concern, not absence).
 */
public final class ComparisonSemantics {

    private ComparisonSemantics() {
        // static truth table, no instances
    }

    /** @return {@code true} iff {@code left op right} holds under the resolved operand values. */
    public static boolean holds(Operator op, Object left, Object right) {
        return switch (op) {
            case EQ -> bothPresent(left, right) && Objects.equals(left, right);
            case NEQ -> bothPresent(left, right) && !Objects.equals(left, right);
            case IN -> left != null && right instanceof Collection<?> col && col.contains(left);
            case NOT_IN -> notIn(left, right);
            case GT -> compareOrdering(left, right, v -> v > 0);
            case GTE -> compareOrdering(left, right, v -> v >= 0);
            case LT -> compareOrdering(left, right, v -> v < 0);
            case LTE -> compareOrdering(left, right, v -> v <= 0);
        };
    }

    private static boolean bothPresent(Object a, Object b) {
        return a != null && b != null;
    }

    /**
     * {@code NOT_IN} holds when both operands are present and {@code left} is not contained in the
     * collection {@code right}. A null operand makes it not hold (ADR-011); a present but
     * non-collection right keeps the prior vacuously-true behaviour (a type concern, not absence).
     */
    private static boolean notIn(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        return !(right instanceof Collection<?> col) || !col.contains(left);
    }

    private static boolean compareOrdering(Object a, Object b, IntPredicate predicate) {
        if (!(a instanceof Number na) || !(b instanceof Number nb)) {
            return false;
        }
        return predicate.test(Double.compare(na.doubleValue(), nb.doubleValue()));
    }
}
