package io.github.ricardoqmd.servicepolicy.enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.ricardoqmd.servicepolicy.domain.model.AuthorizationRequest;
import io.github.ricardoqmd.servicepolicy.domain.model.Resource;
import io.github.ricardoqmd.servicepolicy.domain.model.Subject;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.ConditionEvaluator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;

/**
 * The belt-and-suspenders of ADR-030 §B4: when both operands are fully resolved, the three-valued
 * {@link TriStateConditionEvaluator} must agree with the enforcement {@link ConditionEvaluator} on
 * every operator, in every case — holds, does-not-hold, null operand, non-numeric ordering. Both now
 * route their operator truth through {@code ComparisonSemantics}, so this test is what breaks the
 * build if either evaluator ever stops doing so and the two drift apart.
 *
 * <p>Both operands are literals, so neither evaluator's attribute resolution is in play — the test
 * isolates exactly the shared operator layer. TRUE ⇔ enforcement holds, FALSE ⇔ it does not; the
 * tri-state result can only be TRUE or FALSE here, never INDETERMINATE, because nothing is unresolved.
 */
class TriStateConditionEvaluatorDifferentialTest {

    private static final ConditionEvaluator ENFORCEMENT = new ConditionEvaluator();
    private static final TriStateConditionEvaluator ENUMERATION = new TriStateConditionEvaluator();

    private static final AuthorizationRequest ANY_REQUEST = new AuthorizationRequest(
            "app", new Subject("s", Map.of()), "doc:read", new Resource("doc", "d1", Map.of()), Map.of());
    private static final EnumerationContext ANY_CONTEXT = new EnumerationContext("s", Map.of(), "doc");

    static Stream<Arguments> operandPairs() {
        return Stream.of(
                // EQ / NEQ: holds, does-not-hold, null operand
                Arguments.of(Operator.EQ, 1, 1),
                Arguments.of(Operator.EQ, 1, 2),
                Arguments.of(Operator.EQ, "a", "a"),
                Arguments.of(Operator.EQ, null, 1),
                Arguments.of(Operator.EQ, 1, null),
                Arguments.of(Operator.NEQ, 1, 2),
                Arguments.of(Operator.NEQ, 1, 1),
                Arguments.of(Operator.NEQ, null, 1),
                // IN / NOT_IN: member, non-member, null, non-collection right
                Arguments.of(Operator.IN, "b", List.of("a", "b")),
                Arguments.of(Operator.IN, "z", List.of("a", "b")),
                Arguments.of(Operator.IN, null, List.of("a")),
                Arguments.of(Operator.IN, "a", "not-a-collection"),
                Arguments.of(Operator.NOT_IN, "z", List.of("a", "b")),
                Arguments.of(Operator.NOT_IN, "a", List.of("a", "b")),
                Arguments.of(Operator.NOT_IN, null, List.of("a")),
                Arguments.of(Operator.NOT_IN, "a", "not-a-collection"),
                // Ordering: holds, does-not-hold, null operand, non-numeric operand
                Arguments.of(Operator.GT, 5, 3),
                Arguments.of(Operator.GT, 3, 5),
                Arguments.of(Operator.GT, null, 3),
                Arguments.of(Operator.GT, "abc", 3),
                Arguments.of(Operator.GTE, 5, 5),
                Arguments.of(Operator.GTE, 4, 5),
                Arguments.of(Operator.LT, 3, 5),
                Arguments.of(Operator.LT, 5, 3),
                Arguments.of(Operator.LT, "x", "y"),
                Arguments.of(Operator.LTE, 5, 5),
                Arguments.of(Operator.LTE, 6, 5));
    }

    @ParameterizedTest(name = "{0}({1}, {2})")
    @MethodSource("operandPairs")
    void triStateAgreesWithEnforcementWhenBothOperandsResolve(Operator op, Object left, Object right) {
        Comparison comparison = new Comparison(op, new Literal(left), new Literal(right));

        boolean enforcementHolds = ENFORCEMENT.holds(comparison, ANY_REQUEST);
        ConditionResult enumerationResult = ENUMERATION.evaluate(comparison, ANY_CONTEXT);

        TriState expected = enforcementHolds ? TriState.TRUE : TriState.FALSE;
        assertEquals(
                expected,
                enumerationResult.value(),
                () -> op + " diverged: enforcement holds=" + enforcementHolds + ", enumeration="
                        + enumerationResult.value());
        // Fully resolved operands never yield INDETERMINATE, and settled results carry no refs.
        assertSame(expected, enumerationResult.value());
        assertEquals(0, enumerationResult.contributingResourceAttrs().size());
    }
}
