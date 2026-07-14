package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Condition;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;

/**
 * Maps a domain {@link Policy} to and from a generic document shape ({@code Map<String,Object>}).
 *
 * <p>Storage-agnostic and pure. Persistence-only concerns (the Mongo {@code _id} and the
 * {@code active} flag) are intentionally NOT part of this mapping — they live on the persistence
 * entity, not on the domain policy.
 *
 * <p>{@code app} is likewise not part of the content (ADR-026): it is the scoping coordinate, taken
 * from the request path and stored on the head. A document that carries an {@code app} field is
 * rejected — even if it agrees with the path — so that a body can never contradict the route.
 */
public class PolicyDocumentMapper {

    private static final String APP = "app";
    private static final String POLICY_ID = "policyId";
    private static final String VERSION = "version";
    private static final String RESOURCE_TYPE = "resourceType";
    private static final String ACTIONS = "actions";
    private static final String COMBINING_ALGORITHM = "combiningAlgorithm";
    private static final String DEFAULT_EFFECT = "defaultEffect";
    private static final String RULES = "rules";
    private static final String RULE_ID = "id";
    private static final String RULE_EFFECT = "effect";
    private static final String RULE_CONDITION = "condition";

    private final ConditionDocumentMapper conditionMapper;

    public PolicyDocumentMapper(ConditionDocumentMapper conditionMapper) {
        this.conditionMapper = conditionMapper;
    }

    public Map<String, Object> toDocument(Policy policy) {
        List<Object> rules = new ArrayList<>(policy.rules().size());
        for (Rule rule : policy.rules()) {
            Map<String, Object> ruleDoc = new LinkedHashMap<>();
            ruleDoc.put(RULE_ID, rule.id());
            ruleDoc.put(RULE_EFFECT, rule.effect().name());
            ruleDoc.put(RULE_CONDITION, conditionMapper.toDocument(rule.condition()));
            rules.add(ruleDoc);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(POLICY_ID, policy.id());
        doc.put(VERSION, policy.version());
        doc.put(RESOURCE_TYPE, policy.resourceType());
        doc.put(ACTIONS, new ArrayList<>(policy.actions()));
        doc.put(COMBINING_ALGORITHM, policy.combiningAlgorithm().name());
        doc.put(DEFAULT_EFFECT, policy.defaultEffect().name());
        doc.put(RULES, rules);
        return doc;
    }

    public Policy fromDocument(Map<String, Object> doc) {
        if (doc.containsKey(APP)) {
            throw new PolicyDocumentException(
                    "'" + APP + "' must not be present in the body; it is determined by the path");
        }
        return new Policy(
                requireString(doc, POLICY_ID),
                requireInt(doc, VERSION),
                requireString(doc, RESOURCE_TYPE),
                stringList(doc.get(ACTIONS)),
                parseCombiningAlgorithm(doc.get(COMBINING_ALGORITHM)),
                parseEffect(doc.get(DEFAULT_EFFECT), DEFAULT_EFFECT),
                rulesFrom(doc.get(RULES)));
    }

    private List<Rule> rulesFrom(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new PolicyDocumentException("policy '" + RULES + "' must be a list: " + raw);
        }
        List<Rule> rules = new ArrayList<>(list.size());
        for (Object element : list) {
            Map<String, Object> ruleDoc = asMap(element);
            Condition condition = conditionMapper.fromDocument(asMap(ruleDoc.get(RULE_CONDITION)));
            rules.add(new Rule(
                    requireString(ruleDoc, RULE_ID), parseEffect(ruleDoc.get(RULE_EFFECT), RULE_EFFECT), condition));
        }
        return rules;
    }

    private static CombiningAlgorithm parseCombiningAlgorithm(Object raw) {
        if (!(raw instanceof String name)) {
            throw new PolicyDocumentException("'" + COMBINING_ALGORITHM + "' must be a string: " + raw);
        }
        try {
            return CombiningAlgorithm.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new PolicyDocumentException("unknown combining algorithm: " + name);
        }
    }

    private static Effect parseEffect(Object raw, String field) {
        if (!(raw instanceof String name)) {
            throw new PolicyDocumentException("'" + field + "' must be a string: " + raw);
        }
        try {
            return Effect.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new PolicyDocumentException("unknown effect: " + name);
        }
    }

    private static String requireString(Map<String, Object> doc, String field) {
        if (doc.get(field) instanceof String value) {
            return value;
        }
        throw new PolicyDocumentException("missing or non-string '" + field + "': " + doc.get(field));
    }

    private static int requireInt(Map<String, Object> doc, String field) {
        if (doc.get(field) instanceof Number value) {
            return value.intValue();
        }
        throw new PolicyDocumentException("missing or non-numeric '" + field + "': " + doc.get(field));
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new PolicyDocumentException("expected a list of strings, got: " + raw);
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String value)) {
                throw new PolicyDocumentException("expected a string in list, got: " + element);
            }
            result.add(value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new PolicyDocumentException("expected an object, got: " + value);
    }
}
