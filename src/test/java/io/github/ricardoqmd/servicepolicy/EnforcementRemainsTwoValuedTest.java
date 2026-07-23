package io.github.ricardoqmd.servicepolicy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

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
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHeadRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * The enforcement half of ADR-030 §4: {@code /evaluate} is two-valued and fail-safe, and no
 * three-valued mode is reachable through it. An operand that cannot be resolved — the very case
 * enumeration reports as {@code INDETERMINATE} (conditional) — must, on the enforcement path, resolve
 * to deny.
 *
 * <p>The isolation is also structural (ArchUnit R6/R7 forbid {@code evaluation}/{@code domain} from
 * importing {@code enumeration}); this test pins the runtime behaviour that isolation exists to
 * protect. If the three-valued mode ever leaked into enforcement, a policy that must deny an
 * unresolved operand would instead permit it — the single most dangerous defect this component could
 * ship — and this assertion would fail.
 */
@QuarkusTest
class EnforcementRemainsTwoValuedTest {

    private static final String APP = "enforcement-guard-app";

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    PolicyHeadRepository headRepository;

    @Inject
    PolicyVersionRepository versionRepository;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        headRepository.deleteAll();
        versionRepository.deleteAll();
        catalogueRepository.deleteAll();
    }

    @Test
    @TestSecurity(user = "test-user")
    void anUnresolvableOperandDeniesAtEnforcement() {
        // PERMIT only if resource.attr.clearance > 5 — an operand no request without the instance can
        // resolve. Under enumeration this pair would be conditional; under enforcement it must deny.
        ActionCatalogueTestSupport.declare(catalogueRepository, APP, "document", "read");
        Policy policy = new Policy(
                "instance-gated",
                1,
                "document",
                List.of("read"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "clearance-gate",
                        Effect.PERMIT,
                        new Comparison(Operator.GT, new AttributeRef("resource.attr.clearance"), new Literal(5)))));
        lifecycleStore.create(APP, policy, "seed", null);
        lifecycleStore.activate(APP, "instance-gated", 1, 0L, "seed", null);

        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "action": "document:read",
                          "resource": {"type": "document", "id": "d1"}
                        }
                        """)
                .when()
                .post("/v1/apps/{app}/evaluate", APP)
                .then()
                .statusCode(200)
                .body("allowed", equalTo(false));
    }
}
