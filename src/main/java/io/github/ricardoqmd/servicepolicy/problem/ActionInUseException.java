package io.github.ricardoqmd.servicepolicy.problem;

import java.util.List;

/**
 * An action still referenced by an <em>active</em> policy cannot be removed from the catalogue
 * (ADR-028 §consequences: removing a verb is not safe by construction, and the decided default is
 * to reject rather than to soft-deprecate).
 *
 * <p>Carries the ids of the active policies that block the write, so the client can show the author
 * exactly what to deactivate or re-author first instead of guessing.
 */
public class ActionInUseException extends ProblemException {

    private final List<String> policyIds;

    public ActionInUseException(String app, String resourceType, List<String> actions, List<String> policyIds) {
        super(
                409,
                "ACTION_IN_USE",
                "Action(s) " + actions + " of resource type '" + resourceType + "' in app '" + app
                        + "' are referenced by active policies " + policyIds + ".");
        this.policyIds = List.copyOf(policyIds);
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Action in use",
                getStatus(),
                getMessage(),
                null,
                null,
                null,
                null,
                policyIds);
    }
}
