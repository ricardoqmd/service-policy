package io.github.ricardoqmd.servicepolicy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Persistence round-trip through a real MongoDB (Quarkus Dev Services — Docker required).
 * Proves a domain policy, including its nested condition AST, survives store + reload, and that
 * the active/resource-type query is correct.
 */
@QuarkusTest
class PolicyStoreTest {

    @Inject
    PolicyStore store;

    @Inject
    PolicyRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private Policy documentReadAccess() {
        return new Policy(
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
    void savesAndLoadsActivePolicyByResourceType() {
        Policy policy = documentReadAccess();
        store.save(policy, true);

        List<Policy> loaded = store.activePoliciesFor("document");

        assertEquals(1, loaded.size());
        assertEquals(policy, loaded.get(0));
    }

    @Test
    void ignoresInactivePolicies() {
        store.save(documentReadAccess(), false);
        assertTrue(store.activePoliciesFor("document").isEmpty());
    }

    @Test
    void ignoresOtherResourceTypes() {
        store.save(documentReadAccess(), true);
        assertTrue(store.activePoliciesFor("folder").isEmpty());
    }
}
