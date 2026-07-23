package io.github.ricardoqmd.servicepolicy.domain.policy;

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
 * <p>An <em>absent</em> attribute resolves to {@code null}, and a null or non-numeric operand
 * makes an ordering comparison not hold (ADR-011, ADR-023): neither absence nor a runtime type
 * mismatch ever grants access or raises a server error. Static type mismatches (a non-numeric
 * literal on an ordering operator) are rejected at authoring time by the document mapper.
 *
 * <p>The operator truth itself lives in {@link ComparisonSemantics}, shared with the enumeration
 * path (ADR-030); this class owns only attribute resolution and the fail-safe reading of absence.
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
        // The operator truth lives in ComparisonSemantics, shared with the enumeration path (ADR-030)
        // so the eight operators cannot mean two different things in two places. The fail-safe reading
        // of the unknown is expressed here, by resolving an absent attribute to null and letting the
        // shared rules make the comparison not hold (ADR-011, ADR-023).
        return ComparisonSemantics.holds(cmp.op(), left, right);
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
}
