package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
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
import io.github.ricardoqmd.servicepolicy.persistence.PolicyRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * End-to-end scenarios for {@code POST /v1/evaluate} against the persistence-backed evaluator with
 * a real seeded policy (the neutral ADR-008 domain: {@code document} / {@code area} / assignment).
 *
 * <p>Each test seeds a single active {@code document} policy with the three MVP rules, exercises the
 * full chain (REST → JWT subject → PolicyStore → PolicySelector → PolicyEngine → Decision) against a
 * MongoDB started by Dev Services, and asserts the decision and its audit reason.
 *
 * <p>Uses a fake unsigned JWT (subject {@code test-user}) since signature verification is deferred
 * to Phase 3 (ADR-003).
 */
@QuarkusTest
class EvaluatePolicyScenariosTest {

    private static final String AUTH = "Bearer " + fakeToken("test-user");

    @Inject
    PolicyStore policyStore;

    @Inject
    PolicyRepository policyRepository;

    @BeforeEach
    void seedSingleActiveDocumentPolicy() {
        policyRepository.deleteAll();
        policyStore.save(documentAccessPolicy(), true);
    }

    @AfterEach
    void clearPolicies() {
        policyRepository.deleteAll();
    }

    @Test
    void permitWhenSubjectIsAnAssignee() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1", "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule assigned-access"))
                .body("policyVersion", equalTo("1"))
                .body("decisionId", notNullValue());
    }

    @Test
    void permitWhenSubjectAreaMatchesResourceArea() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d2", "attributes": {"area": "A"}},
                          "subjectAttributes": {"area": "A"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule area-scope"));
    }

    @Test
    void denyWhenResourceSealedEvenIfSubjectIsAssignee() {
        // deny-overrides: sealed-deny beats the assigned-access permit.
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {
                            "type": "document", "id": "d3",
                            "attributes": {"assignees": ["test-user"], "sealed": true}
                          }
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("denied by rule sealed-deny"));
    }

    @Test
    void denyByDefaultWhenNoRuleMatches() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {
                            "type": "document", "id": "d4",
                            "attributes": {"assignees": ["someone-else"], "area": "B"}
                          },
                          "subjectAttributes": {"area": "A"}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("default effect (DENY) of policy doc-access"));
    }

    @Test
    void missingSubjectAreaFallsThroughToDefaultDeny() {
        // area-scope needs subject.attr.area; omitted here -> null does not equal "A" -> no match.
        // An absent attribute means less privilege, never an error.
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d5", "attributes": {"area": "A"}}
                        }
                        """)
                .when()
                .post("/v1/evaluate")
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("default effect (DENY) of policy doc-access"));
    }

    /**
     * The neutral ADR-008 document policy: assignment permit, area-scope permit, sealed deny;
     * deny-overrides; default deny.
     */
    private static Policy documentAccessPolicy() {
        Rule assignedAccess = new Rule(
                "assigned-access",
                Effect.PERMIT,
                new Comparison(
                        Operator.IN, new AttributeRef("subject.id"), new AttributeRef("resource.attr.assignees")));
        Rule areaScope = new Rule(
                "area-scope",
                Effect.PERMIT,
                new Comparison(
                        Operator.EQ, new AttributeRef("resource.attr.area"), new AttributeRef("subject.attr.area")));
        Rule sealedDeny = new Rule(
                "sealed-deny",
                Effect.DENY,
                new Comparison(Operator.EQ, new AttributeRef("resource.attr.sealed"), new Literal(true)));
        return new Policy(
                "doc-access",
                1,
                "document",
                List.of("*"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(assignedAccess, areaScope, sealedDeny));
    }

    private static String fakeToken(String sub) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
