package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

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
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * End-to-end scenarios for {@code POST /v1/apps/{app}/evaluate} against the persistence-backed
 * evaluator with a real seeded policy (the neutral ADR-008 domain: {@code document} / {@code area} /
 * assignment).
 *
 * <p>Each test seeds a single active {@code document} policy through the head-pointer model
 * (ADR-021): {@code create} then {@code activate}. This exercises the full production path — REST →
 * JWT subject → PolicyLifecycleStore → PolicySelector → PolicyEngine → Decision — against a MongoDB
 * started by Dev Services.
 *
 * <p>Uses {@code @TestSecurity} to supply the authenticated identity (ADR-013).
 */
@QuarkusTest
class EvaluatePolicyScenariosTest {

    private static final String APP = "test-app";

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @BeforeEach
    void seedSingleActiveDocumentPolicy() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        // create (revision=0) then activate version 1 with ifMatch=0L (ADR-020).
        lifecycleStore.create(APP, documentAccessPolicy(), "seed-subject", "seed");
        lifecycleStore.activate(APP, "doc-access", 1, 0L, "seed-subject", "seed");
    }

    @AfterEach
    void clearPolicies() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
    }

    @Test
    @TestSecurity(user = "test-user")
    void permitWhenSubjectIsAnAssignee() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1", "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule assigned-access"))
                .body("policyVersion", equalTo("1"))
                .body("decisionId", notNullValue());
    }

    @Test
    @TestSecurity(user = "test-user")
    void permitWhenSubjectAreaMatchesResourceArea() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d2", "attributes": {"area": "A"}},
                          "subjectAttributes": {"area": "A"}
                        }
                        """)
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true))
                .body("reason", equalTo("permitted by rule area-scope"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void denyWhenResourceSealedEvenIfSubjectIsAssignee() {
        given().contentType(ContentType.JSON)
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
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("denied by rule sealed-deny"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void denyByDefaultWhenNoRuleMatches() {
        given().contentType(ContentType.JSON)
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
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("default effect (DENY) of policy doc-access"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void missingSubjectAreaFallsThroughToDefaultDeny() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d5", "attributes": {"area": "A"}}
                        }
                        """)
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false))
                .body("reason", equalTo("default effect (DENY) of policy doc-access"));
    }

    @Test
    @TestSecurity(user = "test-user")
    void policyCreatedButNotActivatedDoesNotContributeToEvaluation() {
        // Reset to a clean slate with only an INACTIVE policy — deliberately skipping activate().
        headRepository.deleteAll();
        versionRepository.deleteAll();
        lifecycleStore.create(APP, documentAccessPolicy(), "seed-subject", "seed");
        // NOT activated: the evaluator must not see this policy.

        // This request WOULD be permitted by doc-access if it were active (subject is an assignee).
        // Because the policy is inactive, no candidates are found and the decision must be DENY.
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d6", "attributes": {"assignees": ["test-user"]}}
                        }
                        """)
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }

    @Test
    @TestSecurity(
            user = "admin-user",
            roles = {"authz-admin"})
    void evaluateDeniesAfterPolicyIsDeactivated() {
        // @BeforeEach seeded doc-access (create at revision=0, then activate -> revision=1).
        String etag = given().when()
                .get("/v1/apps/{app}/policies/doc-access", APP)
                .then()
                .statusCode(200)
                .extract()
                .header("ETag");

        String body = """
                {
                  "action": "document:read",
                  "resource": {"type": "document", "id": "d-cycle",
                               "attributes": {"assignees": ["admin-user"]}}
                }
                """;

        // Step 1: active policy → permit (admin-user is in assignees).
        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(true));

        // Step 2: deactivate — policy becomes invisible to the evaluator.
        given().contentType(ContentType.JSON)
                .header("If-Match", etag)
                .when()
                .post("/v1/apps/{app}/policies/doc-access/deactivate", APP)
                .then()
                .statusCode(200);

        // Step 3: same request → deny (no active candidates for the resource type).
        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
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
}
