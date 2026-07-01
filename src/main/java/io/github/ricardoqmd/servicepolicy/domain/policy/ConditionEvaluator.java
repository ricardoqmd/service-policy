package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.Collection;
import java.util.Objects;

import io.github.ricardoqmd.servicepolicy.domain.exception.PolicyTypeException;
import io.github.ricardoqmd.servicepolicy.domain.exception.UnknownAttributeException;
import io.github.ricardoqmd.servicepolicy.domain.model.AuthorizationRequest;

/**
 * Evaluates a {@link Condition} tree against an {@link AuthorizationRequest}.
 *
 * <p>Pure and stateless: same input, same output, no I/O. Attribute references are
 * resolved by dotted path against the request's open bags; the evaluator hardcodes no
 * domain attribute names. An unknown path fails loudly ({@link UnknownAttributeException})
 * instead of silently — the safe default for authorization.
 *
 * <p>An <em>absent</em> attribute resolves to {@code null}, and a null operand makes a
 * comparison not hold (ADR-011): absence never grants access and never accidentally matches.
 * This differs from a <em>present</em> operand of the wrong type (e.g. a non-numeric value in
 * an ordering comparison), which is a policy-authoring error and still fails loudly.
 */
public final class ConditionEvaluator {

    private static final String SUBJECT_ATTR_PREFIX = "subject.attr.";
    private static final String RESOURCE_ATTR_PREFIX = "resource.attr.";
    private static final String CONTEXT_PREFIX = "context.";

    /**
     * @return {@code true} if the condition holds for the given request.
     */
    public boolean holds(Condition condition, AuthorizationRequest request) {
        return switch (condition) {
            case And and -> and.conditions().stream().allMatch(c -> holds(c, request));
            case Or or -> or.conditions().stream().anyMatch(c -> holds(c, request));
            case Comparison comparison -> compare(comparison, request);
        };
    }

    private boolean compare(Comparison cmp, AuthorizationRequest request) {
        Object left = resolve(cmp.left(), request);
        Object right = resolve(cmp.right(), request);
        // An absent attribute resolves to null. A null operand makes the comparison not hold
        // (ADR-011): an absent attribute can neither grant access (permit rules require it present)
        // nor accidentally match. A present operand of the wrong type remains a policy-authoring
        // error and still fails loudly (see compareNumbers).
        return switch (cmp.op()) {
            case EQ -> bothPresent(left, right) && Objects.equals(left, right);
            case NEQ -> bothPresent(left, right) && !Objects.equals(left, right);
            case IN -> left != null && right instanceof Collection<?> col && col.contains(left);
            case NOT_IN -> notIn(left, right);
            case GT -> bothPresent(left, right) && compareNumbers(left, right) > 0;
            case GTE -> bothPresent(left, right) && compareNumbers(left, right) >= 0;
            case LT -> bothPresent(left, right) && compareNumbers(left, right) < 0;
            case LTE -> bothPresent(left, right) && compareNumbers(left, right) <= 0;
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

    private Object resolve(Operand operand, AuthorizationRequest request) {
        return switch (operand) {
            case Literal literal -> literal.value();
            case AttributeRef ref -> lookup(ref.path(), request);
        };
    }

    private Object lookup(String path, AuthorizationRequest request) {
        if (path.startsWith(SUBJECT_ATTR_PREFIX)) {
            return request.subject().attributes().get(path.substring(SUBJECT_ATTR_PREFIX.length()));
        }
        if (path.startsWith(RESOURCE_ATTR_PREFIX)) {
            return request.resource().attributes().get(path.substring(RESOURCE_ATTR_PREFIX.length()));
        }
        if (path.startsWith(CONTEXT_PREFIX)) {
            return request.context().get(path.substring(CONTEXT_PREFIX.length()));
        }
        return switch (path) {
            case "subject.id" -> request.subject().id();
            case "resource.type" -> request.resource().type();
            case "resource.id" -> request.resource().id();
            default -> throw new UnknownAttributeException(path);
        };
    }

    private static int compareNumbers(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        throw new PolicyTypeException("ordering operators require numeric operands, got: " + a + " and " + b);
    }
}
