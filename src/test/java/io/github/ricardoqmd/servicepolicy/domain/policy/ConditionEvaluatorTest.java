package io.github.ricardoqmd.servicepolicy.domain.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.exception.UnknownAttributeException;
import io.github.ricardoqmd.servicepolicy.domain.model.AuthorizationRequest;
import io.github.ricardoqmd.servicepolicy.domain.model.Resource;
import io.github.ricardoqmd.servicepolicy.domain.model.Subject;

/** Unit tests for {@link ConditionEvaluator}. Pure domain — no Quarkus context needed. */
class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    /** Request: subject u1 (role reviewer, area A, level 5); document in area A, assignees [u1, u7], sealed. */
    private AuthorizationRequest request() {
        Subject subject = new Subject("u1", Map.of("role", "reviewer", "area", "A", "level", 5));
        Resource resource = new Resource(
                "document", "doc-9", Map.of("area", "A", "assignees", List.of("u1", "u7"), "sealed", true));
        return new AuthorizationRequest(subject, "document:read", resource, Map.of("emergency", false));
    }

    private static Comparison cmp(Operator op, Operand left, Operand right) {
        return new Comparison(op, left, right);
    }

    private static AttributeRef attr(String path) {
        return new AttributeRef(path);
    }

    private static Literal lit(Object value) {
        return new Literal(value);
    }

    // ---- operators ----

    @Test
    void eqTrueAndFalse() {
        assertTrue(evaluator.holds(cmp(Operator.EQ, attr("resource.attr.area"), attr("subject.attr.area")), request()));
        assertFalse(evaluator.holds(cmp(Operator.EQ, attr("subject.attr.area"), lit("Z")), request()));
    }

    @Test
    void neq() {
        assertTrue(evaluator.holds(cmp(Operator.NEQ, attr("subject.attr.area"), lit("Z")), request()));
        assertFalse(evaluator.holds(cmp(Operator.NEQ, attr("subject.attr.area"), lit("A")), request()));
    }

    @Test
    void inAndNotIn() {
        assertTrue(evaluator.holds(cmp(Operator.IN, attr("subject.id"), attr("resource.attr.assignees")), request()));
        assertFalse(evaluator.holds(cmp(Operator.IN, lit("u9"), attr("resource.attr.assignees")), request()));
        assertTrue(evaluator.holds(cmp(Operator.NOT_IN, lit("u9"), attr("resource.attr.assignees")), request()));
        assertFalse(
                evaluator.holds(cmp(Operator.NOT_IN, attr("subject.id"), attr("resource.attr.assignees")), request()));
    }

    @Test
    void notInWhenRightIsNotCollection() {
        // right operand not a collection -> NOT_IN is vacuously true
        assertTrue(evaluator.holds(cmp(Operator.NOT_IN, lit("x"), lit("not-a-list")), request()));
    }

    @Test
    void ordering() {
        assertTrue(evaluator.holds(cmp(Operator.GT, attr("subject.attr.level"), lit(3)), request()));
        assertFalse(evaluator.holds(cmp(Operator.GT, attr("subject.attr.level"), lit(5)), request()));
        assertTrue(evaluator.holds(cmp(Operator.GTE, attr("subject.attr.level"), lit(5)), request()));
        assertTrue(evaluator.holds(cmp(Operator.LT, attr("subject.attr.level"), lit(9)), request()));
        assertFalse(evaluator.holds(cmp(Operator.LT, attr("subject.attr.level"), lit(5)), request()));
        assertTrue(evaluator.holds(cmp(Operator.LTE, attr("subject.attr.level"), lit(5)), request()));
    }

    @Test
    void orderingOnNonNumericRuntimeOperandDenies() {
        // ADR-023: a non-numeric attribute value makes the ordering comparison not hold (deny),
        // never a server error. subject.attr.area resolves to "A" (a string), not a Number.
        assertFalse(evaluator.holds(cmp(Operator.GT, attr("subject.attr.area"), lit(1)), request()));
    }

    // ---- composites ----

    @Test
    void andRequiresAll() {
        Condition both = new And(List.of(
                cmp(Operator.EQ, attr("subject.attr.role"), lit("reviewer")),
                cmp(Operator.EQ, attr("resource.attr.area"), attr("subject.attr.area"))));
        assertTrue(evaluator.holds(both, request()));

        Condition oneFails = new And(List.of(
                cmp(Operator.EQ, attr("subject.attr.role"), lit("reviewer")),
                cmp(Operator.EQ, attr("subject.attr.area"), lit("Z"))));
        assertFalse(evaluator.holds(oneFails, request()));
    }

    @Test
    void orRequiresAny() {
        Condition either = new Or(List.of(
                cmp(Operator.EQ, attr("subject.attr.area"), lit("Z")),
                cmp(Operator.IN, attr("subject.id"), attr("resource.attr.assignees"))));
        assertTrue(evaluator.holds(either, request()));

        Condition noneHold = new Or(List.of(
                cmp(Operator.EQ, attr("subject.attr.area"), lit("Z")),
                cmp(Operator.EQ, attr("subject.id"), lit("u9"))));
        assertFalse(evaluator.holds(noneHold, request()));
    }

    @Test
    void nestedComposite() {
        Condition nested = new And(List.of(
                new Or(List.of(
                        cmp(Operator.EQ, attr("subject.attr.role"), lit("admin")),
                        cmp(Operator.EQ, attr("subject.attr.role"), lit("reviewer")))),
                cmp(Operator.EQ, attr("resource.attr.sealed"), lit(true))));
        assertTrue(evaluator.holds(nested, request()));
    }

    // ---- lookups ----

    @Test
    void resolvesFixedPaths() {
        assertTrue(evaluator.holds(cmp(Operator.EQ, attr("resource.type"), lit("document")), request()));
        assertTrue(evaluator.holds(cmp(Operator.EQ, attr("resource.id"), lit("doc-9")), request()));
        assertTrue(evaluator.holds(cmp(Operator.EQ, attr("subject.id"), lit("u1")), request()));
    }

    @Test
    void resolvesContext() {
        assertTrue(evaluator.holds(cmp(Operator.EQ, attr("context.emergency"), lit(false)), request()));
    }

    @Test
    void missingBagKeyResolvesToNull() {
        // unknown KEY inside a known bag -> null (not an error); area Z != null
        assertFalse(evaluator.holds(cmp(Operator.EQ, attr("subject.attr.unknownKey"), lit("x")), request()));
    }

    @Test
    void unknownPathThrows() {
        assertThrows(
                UnknownAttributeException.class,
                () -> evaluator.holds(cmp(Operator.EQ, attr("subject.salary"), lit(1)), request()));
    }

    // ---- null operands: an absent attribute never satisfies a comparison (ADR-011) ----

    @Test
    void eqWithBothOperandsAbsentDoesNotHold() {
        // the over-permit this fixes: null EQ null must NOT match.
        assertFalse(evaluator.holds(
                cmp(Operator.EQ, attr("subject.attr.absent"), attr("resource.attr.absent")), request()));
    }

    @Test
    void eqWithOneAbsentOperandDoesNotHold() {
        assertFalse(evaluator.holds(cmp(Operator.EQ, attr("subject.attr.absent"), lit("A")), request()));
    }

    @Test
    void neqWithAbsentOperandDoesNotHold() {
        // an absent attribute must not satisfy NEQ either, or a NEQ-based rule would over-match.
        assertFalse(evaluator.holds(cmp(Operator.NEQ, attr("subject.attr.absent"), lit("A")), request()));
    }

    @Test
    void inWithAbsentLeftDoesNotHold() {
        assertFalse(evaluator.holds(
                cmp(Operator.IN, attr("subject.attr.absent"), attr("resource.attr.assignees")), request()));
    }

    @Test
    void notInWithAbsentRightCollectionDoesNotHold() {
        // an absent collection makes NOT_IN not hold — distinct from a present non-collection,
        // which stays vacuously true (a type concern, not absence).
        assertFalse(evaluator.holds(cmp(Operator.NOT_IN, lit("x"), attr("resource.attr.absent")), request()));
    }

    @Test
    void orderingWithAbsentOperandDoesNotHoldAndDoesNotThrow() {
        // contrast orderingOnNonNumberThrows: an absent operand is no-match, not an error.
        assertFalse(evaluator.holds(cmp(Operator.GT, attr("subject.attr.absent"), lit(3)), request()));
    }
}
