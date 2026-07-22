package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.List;

import jakarta.inject.Singleton;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.problem.PolicyValidationException;
import io.github.ricardoqmd.servicepolicy.problem.ProblemDetail;

/**
 * Resolves a policy's {@code actions} against the action catalogue of its {@code (app,
 * resourceType)} at authoring time (ADR-028): expands {@code ["*"]} to the explicit catalogue list
 * and rejects any action the application does not claim to have.
 *
 * <p>Two behaviours, one reason. {@code *} is input sugar that never reaches storage and never
 * reaches the evaluator: expanding it here converts a silent, deferred widening — a policy written
 * as {@code ["*"]} would start governing every verb added later — into an explicit, versioned
 * authoring act. Validating explicit actions closes the same hole from the other side: a policy
 * cannot govern a verb that does not exist.
 *
 * <p>Failures are {@code INVALID_POLICY} (400) through the ADR-023 mechanism, with {@code actions}
 * as the offending field. No new error code: an uncatalogued action is a bad policy document, not a
 * new error class.
 *
 * <p>Depends only on {@link ActionCatalogueRepository}, never on {@link ActionCatalogueStore} — the
 * store depends on {@link PolicyLifecycleStore}, which depends on this resolver, so the bean graph
 * stays acyclic.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class ActionCatalogueResolver {

    private static final String WILDCARD = "*";
    private static final String ACTIONS = "actions";

    private final ActionCatalogueRepository repository;

    ActionCatalogueResolver(ActionCatalogueRepository repository) {
        this.repository = repository;
    }

    /**
     * Expands {@code '*'} and validates actions against the catalogue of {@code (app,
     * policy.resourceType())}.
     *
     * @return the policy with {@code actions} guaranteed explicit and catalogued — the expanded copy
     *     when the input was {@code ["*"]}, the input itself otherwise.
     * @throws PolicyValidationException (400 {@code INVALID_POLICY}) if {@code '*'} is mixed with
     *     other elements, if the resource type has no catalogue in this app, or if any action is not
     *     in it.
     */
    public Policy resolve(String app, Policy policy) {
        List<String> actions = policy.actions();
        if (actions.contains(WILDCARD) && actions.size() > 1) {
            throw invalid("'" + WILDCARD + "' must be the only element of '" + ACTIONS + "'");
        }

        List<String> catalogue = repository
                .findByAppAndResourceType(app, policy.resourceType())
                .map(document -> document.actions)
                .orElseThrow(() -> invalid("no action catalogue for resource type '" + policy.resourceType()
                        + "' in this app; declare the catalogue before authoring"));

        if (actions.contains(WILDCARD)) {
            return withActions(policy, catalogue);
        }

        List<ProblemDetail.InvalidParam> unknown = actions.stream()
                .filter(action -> !catalogue.contains(action))
                .map(action -> new ProblemDetail.InvalidParam(
                        ACTIONS,
                        "action '" + action + "' is not in the catalogue of resource type '" + policy.resourceType()
                                + "'"))
                .toList();
        if (!unknown.isEmpty()) {
            throw new PolicyValidationException(unknown);
        }
        return policy;
    }

    /** The expanded policy keeps everything but its action list, which becomes the catalogue's. */
    private static Policy withActions(Policy policy, List<String> actions) {
        return new Policy(
                policy.id(),
                policy.version(),
                policy.resourceType(),
                actions,
                policy.combiningAlgorithm(),
                policy.defaultEffect(),
                policy.rules());
    }

    private static PolicyValidationException invalid(String reason) {
        return new PolicyValidationException(List.of(new ProblemDetail.InvalidParam(ACTIONS, reason)));
    }
}
