package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.And;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Or;

class ConditionDocumentMapperTest {

    private final ConditionDocumentMapper mapper = new ConditionDocumentMapper();

    private Condition roundTrip(Condition condition) {
        return mapper.fromDocument(mapper.toDocument(condition));
    }

    @Test
    void roundTripComparison() {
        Condition c = new Comparison(
                Operator.IN, new AttributeRef("subject.id"), new AttributeRef("resource.attr.assignees"));
        assertEquals(c, roundTrip(c));
    }

    @Test
    void roundTripNestedAndOr() {
        Condition c = new And(List.of(
                new Or(List.of(
                        new Comparison(Operator.EQ, new AttributeRef("subject.attr.role"), new Literal("admin")),
                        new Comparison(Operator.EQ, new AttributeRef("subject.attr.role"), new Literal("reviewer")))),
                new Comparison(Operator.EQ, new AttributeRef("resource.attr.sealed"), new Literal(true))));
        assertEquals(c, roundTrip(c));
    }

    @Test
    void roundTripLiteralTypes() {
        assertEquals(new Literal("text"), roundTripLiteral(new Literal("text")));
        assertEquals(new Literal(true), roundTripLiteral(new Literal(true)));
        assertEquals(new Literal(42), roundTripLiteral(new Literal(42)));
        assertEquals(new Literal(null), roundTripLiteral(new Literal(null)));
    }

    private Object roundTripLiteral(Literal literal) {
        Condition c = new Comparison(Operator.EQ, new AttributeRef("x"), literal);
        Comparison out = (Comparison) roundTrip(c);
        return out.right();
    }

    @Test
    void comparisonDocumentShape() {
        Map<String, Object> doc = mapper.toDocument(
                new Comparison(Operator.EQ, new AttributeRef("resource.attr.area"), new Literal("A")));
        assertEquals("comparison", doc.get("type"));
        assertEquals("EQ", doc.get("op"));
        assertEquals(Map.of("ref", "resource.attr.area"), doc.get("left"));
        assertEquals(Map.of("value", "A"), doc.get("right"));
    }

    @Test
    void unknownTypeThrows() {
        assertThrows(PolicyDocumentException.class, () -> mapper.fromDocument(Map.of("type", "nope")));
    }

    @Test
    void unknownOperatorThrows() {
        Map<String, Object> doc =
                Map.of("type", "comparison", "op", "BOGUS", "left", Map.of("ref", "x"), "right", Map.of("value", 1));
        assertThrows(PolicyDocumentException.class, () -> mapper.fromDocument(doc));
    }

    @Test
    void missingTypeThrows() {
        assertThrows(PolicyDocumentException.class, () -> mapper.fromDocument(Map.of("op", "EQ")));
    }

    @Test
    void operandWithoutRefOrValueThrows() {
        Map<String, Object> doc =
                Map.of("type", "comparison", "op", "EQ", "left", Map.of("bogus", "x"), "right", Map.of("value", 1));
        assertThrows(PolicyDocumentException.class, () -> mapper.fromDocument(doc));
    }
}
