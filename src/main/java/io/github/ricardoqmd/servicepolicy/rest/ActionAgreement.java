package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

import io.github.ricardoqmd.servicepolicy.evaluation.EvaluationRequest;
import io.github.ricardoqmd.servicepolicy.problem.ActionResourceTypeMismatchException;
import io.github.ricardoqmd.servicepolicy.problem.InvalidRequestException;

/**
 * Refuses a request whose action names a type other than its resource's (ADR-036).
 *
 * <p>The engine selects policies by the verb alone ({@code PolicySelector}), so the prefix of a
 * {@code type:verb} action used to be read as decoration: {@code document:approve} asked about a
 * {@code payment} was evaluated as {@code payment:approve}. The prefix exists to say what the verb is
 * about, and when it says something else the request contradicts itself — this check refuses it before
 * anything is evaluated, reading nothing but the request.
 *
 * <ul>
 *   <li>The action is split at its <em>first</em> colon, the same split {@code PolicySelector} applies:
 *       {@code a:b:c} has prefix {@code a} and verb {@code b:c}. The verb must not be blank.
 *   <li>An action with no colon is the verb. It names no type, so it cannot contradict one.
 *   <li>The prefix must equal {@code resource.type} exactly. No normalization, no case folding, no
 *       aliasing: two spellings are two types here, as everywhere else in this service.
 * </ul>
 */
final class ActionAgreement {

    private ActionAgreement() {}

    /**
     * Checks one request outside a batch. The caller has already refused a blank action or a blank
     * {@code resource.type}.
     */
    static void check(EvaluationRequest request) {
        check(request.action(), request.resource().type(), null);
    }

    /**
     * Checks every item of a batch before any of them is evaluated: one disagreeing item refuses the
     * whole batch (ADR-031, ADR-036 §5), naming its index.
     *
     * <p>An item without an action or without a {@code resource.type} is left to the path it already
     * takes; there is no pair to compare.
     */
    static void checkEach(List<EvaluationRequest> requests) {
        for (int i = 0; i < requests.size(); i++) {
            EvaluationRequest item = requests.get(i);
            if (item == null
                    || item.action() == null
                    || item.resource() == null
                    || item.resource().type() == null
                    || item.resource().type().isBlank()) {
                continue;
            }
            check(item.action(), item.resource().type(), i);
        }
    }

    private static void check(String action, String resourceType, Integer index) {
        int colon = action.indexOf(':');
        if (colon < 0) {
            return;
        }
        String prefix = action.substring(0, colon);
        if (action.substring(colon + 1).isBlank()) {
            throw new InvalidRequestException((index == null ? "" : "requests[" + index + "]: ")
                    + "the verb of action '" + action + "' must not be blank.");
        }
        if (!prefix.equals(resourceType)) {
            throw new ActionResourceTypeMismatchException(prefix, resourceType, index);
        }
    }
}
