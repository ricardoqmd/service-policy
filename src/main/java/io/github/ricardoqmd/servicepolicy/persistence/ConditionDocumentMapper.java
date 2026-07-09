package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ricardoqmd.servicepolicy.domain.policy.And;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operand;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Or;

/**
 * Maps the domain condition AST to and from a generic document shape ({@code Map<String,Object>}).
 *
 * <p>Pure and storage-agnostic: it produces plain maps/lists that any document store can persist
 * (a MongoDB {@code Document} is itself a {@code Map<String,Object>}), and rebuilds the typed
 * {@link Condition} tree on read. Keeping this out of the domain leaves {@link Condition} free of
 * serialization concerns and keeps the polymorphic recursion unit-testable without a database.
 *
 * <p>Document shapes:
 *
 * <ul>
 *   <li>comparison: {@code {"type":"comparison","op":"EQ","left":<operand>,"right":<operand>}}
 *   <li>and / or: {@code {"type":"and","conditions":[<condition>, ...]}}
 *   <li>operand: {@code {"ref":"subject.id"}} or {@code {"value":<literal>}}
 * </ul>
 */
public class ConditionDocumentMapper {

    private static final String TYPE = "type";
    private static final String OP = "op";
    private static final String LEFT = "left";
    private static final String RIGHT = "right";
    private static final String CONDITIONS = "conditions";
    private static final String REF = "ref";
    private static final String VALUE = "value";

    private static final String TYPE_COMPARISON = "comparison";
    private static final String TYPE_AND = "and";
    private static final String TYPE_OR = "or";

    public Map<String, Object> toDocument(Condition condition) {
        return switch (condition) {
            case Comparison comparison -> {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put(TYPE, TYPE_COMPARISON);
                doc.put(OP, comparison.op().name());
                doc.put(LEFT, operandToDocument(comparison.left()));
                doc.put(RIGHT, operandToDocument(comparison.right()));
                yield doc;
            }
            case And and -> composite(TYPE_AND, and.conditions());
            case Or or -> composite(TYPE_OR, or.conditions());
        };
    }

    public Condition fromDocument(Map<String, Object> doc) {
        if (!(doc.get(TYPE) instanceof String type)) {
            throw new PolicyDocumentException("condition is missing a string '" + TYPE + "': " + doc);
        }
        return switch (type) {
            case TYPE_COMPARISON -> {
                Operator op = parseOperator(doc.get(OP));
                Operand left = operandFromDocument(asMap(doc.get(LEFT), LEFT));
                Operand right = operandFromDocument(asMap(doc.get(RIGHT), RIGHT));
                validateOrderingLiterals(op, left, right);
                yield new Comparison(op, left, right);
            }
            case TYPE_AND -> new And(conditionsFrom(doc));
            case TYPE_OR -> new Or(conditionsFrom(doc));
            default -> throw new PolicyDocumentException("unknown condition type: " + type);
        };
    }

    private Map<String, Object> composite(String type, List<Condition> conditions) {
        List<Object> children = new ArrayList<>(conditions.size());
        for (Condition condition : conditions) {
            children.add(toDocument(condition));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(TYPE, type);
        doc.put(CONDITIONS, children);
        return doc;
    }

    private List<Condition> conditionsFrom(Map<String, Object> doc) {
        if (!(doc.get(CONDITIONS) instanceof List<?> list)) {
            throw new PolicyDocumentException("composite is missing a '" + CONDITIONS + "' list: " + doc);
        }
        List<Condition> conditions = new ArrayList<>(list.size());
        for (Object element : list) {
            conditions.add(fromDocument(asMap(element, CONDITIONS)));
        }
        return conditions;
    }

    private Map<String, Object> operandToDocument(Operand operand) {
        Map<String, Object> doc = new LinkedHashMap<>();
        switch (operand) {
            case AttributeRef ref -> doc.put(REF, ref.path());
            case Literal literal -> doc.put(VALUE, literal.value());
        }
        return doc;
    }

    private Operand operandFromDocument(Map<String, Object> doc) {
        if (doc.containsKey(REF)) {
            if (!(doc.get(REF) instanceof String path)) {
                throw new PolicyDocumentException("operand '" + REF + "' must be a string: " + doc);
            }
            return new AttributeRef(path);
        }
        if (doc.containsKey(VALUE)) {
            return new Literal(doc.get(VALUE));
        }
        throw new PolicyDocumentException("operand must have '" + REF + "' or '" + VALUE + "': " + doc);
    }

    private static void validateOrderingLiterals(Operator op, Operand left, Operand right) {
        if (!op.isOrdering()) {
            return;
        }
        checkOrderingLiteral(op, "left", left);
        checkOrderingLiteral(op, "right", right);
    }

    private static void checkOrderingLiteral(Operator op, String side, Operand operand) {
        if (operand instanceof Literal lit && !(lit.value() instanceof Number)) {
            throw new PolicyDocumentException("operator " + op.name() + " requires a numeric literal for the " + side
                    + " operand, got: " + lit.value());
        }
    }

    private static Operator parseOperator(Object raw) {
        if (!(raw instanceof String name)) {
            throw new PolicyDocumentException("comparison '" + OP + "' must be a string: " + raw);
        }
        try {
            return Operator.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new PolicyDocumentException("unknown operator: " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String field) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new PolicyDocumentException("expected an object for '" + field + "', got: " + value);
    }
}
