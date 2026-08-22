package io.github.ricardoqmd.servicepolicy.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import io.github.ricardoqmd.servicepolicy.ActionCatalogueTestSupport;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Literal;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.enumeration.SubjectAttributeDeriver;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueRepository;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * ADR-032 §3, proved structurally: {@link SubjectAttributeDeriver} is never consulted on the pushed
 * transport, because {@link PushedEnumerationResource} cannot reach it.
 *
 * <p><strong>Why not over HTTP.</strong> The literal property — "an attribute the caller's token
 * carries and the body does not must not appear in the result" — is not expressible against the
 * running endpoint here: token claims cannot be injected over HTTP with OIDC disabled in the test
 * profile, which {@code PermissionsResourceTest}'s own Javadoc already records. A test that asserted
 * the attribute's absence in the response would pass for that reason alone, and would keep passing if
 * derivation started running tomorrow. Removing the deriver instead makes the wrong behaviour
 * impossible to hide: reaching it throws.
 *
 * <p><strong>The negative control is the load-bearing half.</strong> A {@code null} deriver that no
 * path touches proves nothing at all. {@link #theGetOnTheSameWiringThrowsWithoutADeriver} is what
 * gives the passing pushed call its meaning: on the transport that <em>does</em> derive, the same
 * absent deriver is fatal. One test without the other is theatre.
 *
 * <p>Since the pushed resource does not take a deriver at all, these tests document a property the
 * constructor already guarantees. They are kept because a guarantee that nothing checks is one
 * refactor away from being untrue — {@link #theDeriverIsNeitherAConstructorParameterNorAFieldOfThePushedResource}
 * fails the moment someone wires the dependency back in, before any behaviour has a chance to change.
 */
@QuarkusTest
class DerivationDoesNotRunOnThePushedTransportTest {

    private static final String CALLER = "service-account-backend";
    private static final String OTHER_SUBJECT = "carla";
    private static final String DELEGATION_ROLE = "pdp-client";

    @Inject
    EnumerationResponder responder;

    @Inject
    AuthContext authContext;

    @Inject
    PolicyLifecycleStore lifecycleStore;

    @Inject
    ActionCatalogueRepository catalogueRepository;

    /**
     * The pushed transport answers a delegated enumeration with no deriver anywhere in its wiring, and
     * the body's attributes are what resolved the rule — {@code conditional: false} is only reachable
     * if {@code area} was known, and the only place it could have come from is the request.
     */
    @Test
    @TestSecurity(user = CALLER, roles = DELEGATION_ROLE)
    void thePushedTransportAnswersWithNoDeriverInItsWiring() {
        String app = "no-deriver-pushed";
        seed(app);

        PushedEnumerationResource pushed = new PushedEnumerationResource(responder, authContext);
        Response response = pushed.enumerate(app, new EnumerationRequest(OTHER_SUBJECT, Map.of("area", "north")), null);

        assertEquals(200, response.getStatus());
        PermissionsView view = (PermissionsView) response.getEntity();
        assertEquals(OTHER_SUBJECT, view.subject());
        assertEquals(1, view.permissions().size());
        assertFalse(view.permissions().get(0).conditional(), "the body's attributes must be what resolved the rule");
    }

    /**
     * THE NEGATIVE CONTROL. The same responder, the same context, the same app — but on the transport
     * that derives from claims, an absent deriver is fatal. Without this, the test above would pass
     * just as happily against a resource that did derive.
     */
    @Test
    @TestSecurity(user = CALLER)
    void theGetOnTheSameWiringThrowsWithoutADeriver() {
        String app = "no-deriver-get";
        seed(app);

        PermissionsResource get = new PermissionsResource(responder, null, authContext);

        assertThrows(NullPointerException.class, () -> get.permissions(app, null));
    }

    /** The guard against a future re-wiring: the dependency is absent from the type, not merely unused. */
    @Test
    void theDeriverIsNeitherAConstructorParameterNorAFieldOfThePushedResource() {
        assertFalse(
                Arrays.stream(PushedEnumerationResource.class.getDeclaredConstructors())
                        .flatMap(c -> Arrays.stream(c.getParameterTypes()))
                        .anyMatch(SubjectAttributeDeriver.class::equals),
                "the pushed transport must not be handed a deriver (ADR-032 §3)");

        assertFalse(
                Arrays.stream(PushedEnumerationResource.class.getDeclaredFields())
                        .map(Field::getType)
                        .anyMatch(SubjectAttributeDeriver.class::equals),
                "the pushed transport must not hold a deriver (ADR-032 §3)");
    }

    /** One pair whose only rule reads {@code subject.attr.area}: permit, deny or unresolved. */
    private void seed(String app) {
        ActionCatalogueTestSupport.declare(catalogueRepository, app, "document", "share");

        Policy policy = new Policy(
                "p-share",
                1,
                "document",
                List.of("share"),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "r-permit",
                        Effect.PERMIT,
                        new Comparison(Operator.EQ, new AttributeRef("subject.attr.area"), new Literal("north")))));

        lifecycleStore.create(app, policy, "seed", null);
        lifecycleStore.activate(app, policy.id(), 1, 0L, "seed", null);
    }
}
