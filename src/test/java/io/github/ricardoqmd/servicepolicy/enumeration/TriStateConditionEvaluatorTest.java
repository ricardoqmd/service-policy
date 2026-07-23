package io.github.ricardoqmd.servicepolicy.enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.And;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operand;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Or;

/**
 * Truth tables for three-valued condition evaluation (ADR-030 §B2/§B3 conditions): operand
 * resolvability, Kleene AND/OR settling, and the collection of {@code dependsOn} candidates from only
 * the branches that actually caused indeterminacy.
 */
class TriStateConditionEvaluatorTest {

    private final TriStateConditionEvaluator evaluator = new TriStateConditionEvaluator();

    private final EnumerationContext context = new EnumerationContext("alice", Map.of("area", "north"), "document");

    // ── Operand resolvability (B2) ───────────────────────────────────────────

    @Test
    void resourceAttrIsIndeterminateAndCollectsItsName() {
        ConditionResult result = evaluator.evaluate(eq(attr("resource.attr.areaId"), lit("north")), context);

        assertEquals(TriState.INDETERMINATE, result.value());
        assertEquals(Set.of("areaId"), result.contributingResourceAttrs());
    }

    @Test
    void absentSubjectAttrIsIndeterminateWithNoDependsOn() {
        ConditionResult result = evaluator.evaluate(eq(attr("subject.attr.clearance"), lit(5)), context);

        assertEquals(TriState.INDETERMINATE, result.value());
        assertTrue(result.contributingResourceAttrs().isEmpty(), "subject attributes are never dependsOn names");
    }

    @Test
    void presentSubjectAttrResolves() {
        assertEquals(
                TriState.TRUE,
                evaluator
                        .evaluate(eq(attr("subject.attr.area"), lit("north")), context)
                        .value());
        assertEquals(
                TriState.FALSE,
                evaluator
                        .evaluate(eq(attr("subject.attr.area"), lit("south")), context)
                        .value());
    }

    @Test
    void subjectIdAndResourceTypeResolve() {
        assertEquals(
                TriState.TRUE,
                evaluator
                        .evaluate(eq(attr("subject.id"), lit("alice")), context)
                        .value());
        assertEquals(
                TriState.TRUE,
                evaluator
                        .evaluate(eq(attr("resource.type"), lit("document")), context)
                        .value());
    }

    @Test
    void resourceIdAndContextAreIndeterminateWithNoDependsOn() {
        ConditionResult byId = evaluator.evaluate(eq(attr("resource.id"), lit("d1")), context);
        ConditionResult byContext = evaluator.evaluate(eq(attr("context.emergency"), lit(true)), context);

        assertEquals(TriState.INDETERMINATE, byId.value());
        assertEquals(TriState.INDETERMINATE, byContext.value());
        assertTrue(byId.contributingResourceAttrs().isEmpty());
        assertTrue(byContext.contributingResourceAttrs().isEmpty());
    }

    @Test
    void anUnknownPathIsIndeterminateAndDoesNotThrow() {
        // Enforcement throws UnknownAttributeException here; the advisory path must never 500 (B2).
        ConditionResult result = evaluator.evaluate(eq(attr("bogus.path"), lit("x")), context);

        assertEquals(TriState.INDETERMINATE, result.value());
        assertTrue(result.contributingResourceAttrs().isEmpty());
    }

    // ── OR (B3): a satisfied type-level branch settles it — the ADR flagship ──

    @Test
    void orWithASatisfiedTypeLevelBranchIsDeterministicAndCollectsNoRefs() {
        // subject.attr.area == "north" (TRUE) OR resource.attr.areaId == "north" (INDETERMINATE)
        Condition or = new Or(
                List.of(eq(attr("subject.attr.area"), lit("north")), eq(attr("resource.attr.areaId"), lit("north"))));

        ConditionResult result = evaluator.evaluate(or, context);

        assertEquals(TriState.TRUE, result.value());
        assertTrue(result.contributingResourceAttrs().isEmpty(), "a settled OR asks for nothing");
    }

    @Test
    void orWithNoTrueButAnIndeterminateBranchIsIndeterminateWithUnionRefs() {
        Condition or = new Or(List.of(
                eq(attr("subject.attr.area"), lit("south")), // FALSE
                eq(attr("resource.attr.areaId"), lit("north")), // INDETERMINATE, ref areaId
                eq(attr("resource.attr.owner"), lit("alice")))); // INDETERMINATE, ref owner

        ConditionResult result = evaluator.evaluate(or, context);

        assertEquals(TriState.INDETERMINATE, result.value());
        assertEquals(Set.of("areaId", "owner"), result.contributingResourceAttrs());
    }

    @Test
    void orWithAllFalseBranchesIsFalse() {
        Condition or = new Or(List.of(eq(attr("subject.attr.area"), lit("south")), eq(attr("subject.id"), lit("bob"))));

        assertEquals(TriState.FALSE, evaluator.evaluate(or, context).value());
    }

    // ── AND (B3): a false branch settles it ──────────────────────────────────

    @Test
    void andWithAFalseBranchIsSettledFalseAndCollectsNoRefs() {
        // subject.attr.area == "south" (FALSE) AND resource.attr.areaId == "north" (INDETERMINATE)
        Condition and = new And(
                List.of(eq(attr("subject.attr.area"), lit("south")), eq(attr("resource.attr.areaId"), lit("north"))));

        ConditionResult result = evaluator.evaluate(and, context);

        assertEquals(TriState.FALSE, result.value());
        assertTrue(result.contributingResourceAttrs().isEmpty(), "a settled AND asks for nothing");
    }

    @Test
    void andWithNoFalseButAnIndeterminateBranchIsIndeterminateWithUnionRefs() {
        Condition and = new And(List.of(
                eq(attr("subject.attr.area"), lit("north")), // TRUE
                eq(attr("resource.attr.areaId"), lit("north")), // INDETERMINATE, ref areaId
                eq(attr("resource.attr.clasificacion"), lit("public")))); // INDETERMINATE, ref clasificacion

        ConditionResult result = evaluator.evaluate(and, context);

        assertEquals(TriState.INDETERMINATE, result.value());
        assertEquals(Set.of("areaId", "clasificacion"), result.contributingResourceAttrs());
    }

    @Test
    void andWithAllTrueBranchesIsTrue() {
        Condition and =
                new And(List.of(eq(attr("subject.attr.area"), lit("north")), eq(attr("subject.id"), lit("alice"))));

        assertEquals(TriState.TRUE, evaluator.evaluate(and, context).value());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Comparison eq(Operand left, Operand right) {
        return new Comparison(Operator.EQ, left, right);
    }

    private static AttributeRef attr(String path) {
        return new AttributeRef(path);
    }

    private static Literal lit(Object value) {
        return new Literal(value);
    }
}
