package io.github.ricardoqmd.servicepolicy.problem;

/**
 * An action whose prefix names a type other than the resource's (ADR-036).
 *
 * <p>It has its own code rather than sharing {@code BAD_REQUEST}, because the failure it prevents
 * was silent: the engine used to drop the prefix and answer for the resource's type, so a caller that
 * copied an action from a neighbouring screen received a decision about a question it never asked. A
 * caller that can recognise this rejection knows the request was well-formed and self-contradictory,
 * not merely malformed.
 *
 * <p>Both compared values travel as extension members, and in a batch so does the position of the
 * offending item. None of them discloses anything: the caller sent all three.
 */
public class ActionResourceTypeMismatchException extends ProblemException {

    private final String actionPrefix;
    private final String resourceType;
    private final Integer index;

    /**
     * @param actionPrefix what precedes the first colon of the action.
     * @param resourceType the request's {@code resource.type}.
     * @param index        the item's position in a batch, or {@code null} outside a batch.
     */
    public ActionResourceTypeMismatchException(String actionPrefix, String resourceType, Integer index) {
        super(
                400,
                "ACTION_RESOURCE_TYPE_MISMATCH",
                (index == null ? "" : "requests[" + index + "]: ")
                        + "action prefix '" + actionPrefix + "' does not match resource.type '" + resourceType
                        + "'.");
        this.actionPrefix = actionPrefix;
        this.resourceType = resourceType;
        this.index = index;
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Action does not match resource type",
                getStatus(),
                getMessage(),
                null,
                null,
                null,
                null,
                null,
                null,
                actionPrefix,
                resourceType,
                index);
    }
}
