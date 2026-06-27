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
        return switch (cmp.op()) {
            case EQ -> Objects.equals(left, right);
            case NEQ -> !Objects.equals(left, right);
            case IN -> right instanceof Collection<?> col && col.contains(left);
            case NOT_IN -> !(right instanceof Collection<?> col) || !col.contains(left);
            case GT -> compareNumbers(left, right) > 0;
            case GTE -> compareNumbers(left, right) >= 0;
            case LT -> compareNumbers(left, right) < 0;
            case LTE -> compareNumbers(left, right) <= 0;
        };
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
