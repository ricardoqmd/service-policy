package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;

class PolicyDocumentMapperTest {

    private final PolicyDocumentMapper mapper = new PolicyDocumentMapper(new ConditionDocumentMapper());

    private Policy documentReadAccess() {
        return new Policy(
                "test-app",
                "document-read-access",
                1,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(
                        new Rule(
                                "assigned-access",
                                Effect.PERMIT,
                                new Comparison(
                                        Operator.IN,
                                        new AttributeRef("subject.id"),
                                        new AttributeRef("resource.attr.assignees"))),
                        new Rule(
                                "sealed-deny",
                                Effect.DENY,
                                new Comparison(
                                        Operator.EQ, new AttributeRef("resource.attr.sealed"), new Literal(true)))));
    }

    @Test
    void roundTripPreservesPolicy() {
        Policy original = documentReadAccess();
        assertEquals(original, mapper.fromDocument(mapper.toDocument(original)));
    }

    @Test
    void documentShape() {
        Map<String, Object> doc = mapper.toDocument(documentReadAccess());
        assertEquals("test-app", doc.get("app"));
        assertEquals("document-read-access", doc.get("policyId"));
        assertEquals(1, doc.get("version"));
        assertEquals("document", doc.get("resourceType"));
        assertEquals(List.of("read"), doc.get("actions"));
        assertEquals("DENY_OVERRIDES", doc.get("combiningAlgorithm"));
        assertEquals("DENY", doc.get("defaultEffect"));
        assertEquals(2, ((List<?>) doc.get("rules")).size());
    }

    @Test
    void missingAppThrows() {
        Map<String, Object> doc = mapper.toDocument(documentReadAccess());
        doc.remove("app");
        assertThrows(PolicyDocumentException.class, () -> mapper.fromDocument(doc));
    }

    @Test
    void unknownEffectThrows() {
        Map<String, Object> doc = mapper.toDocument(documentReadAccess());
        ((List<Map<String, Object>>) (List<?>) doc.get("rules")).get(0).put("effect", "MAYBE");
        assertThrows(PolicyDocumentException.class, () -> mapper.fromDocument(doc));
    }

    @Test
    void missingPolicyIdThrows() {
        Map<String, Object> doc = mapper.toDocument(documentReadAccess());
        doc.remove("policyId");
        assertThrows(PolicyDocumentException.class, () -> mapper.fromDocument(doc));
    }

    @Test
    void rulesNotListThrows() {
        Map<String, Object> doc = mapper.toDocument(documentReadAccess());
        doc.put("rules", "not-a-list");
        assertThrows(PolicyDocumentException.class, () -> mapper.fromDocument(doc));
    }
}
