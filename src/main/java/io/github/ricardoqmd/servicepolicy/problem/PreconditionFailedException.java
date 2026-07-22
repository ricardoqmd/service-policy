package io.github.ricardoqmd.servicepolicy.problem;

public class PreconditionFailedException extends ProblemException {

    private final String policyId;
    private final long currentRevision;

    public PreconditionFailedException(String app, String policyId, long currentRevision) {
        super(
                412,
                "PRECONDITION_FAILED",
                "If-Match does not match the current revision " + currentRevision + " of policy '" + policyId
                        + "' in app '" + app + "'.");
        this.policyId = policyId;
        this.currentRevision = currentRevision;
    }

    private PreconditionFailedException(String detail, long currentRevision) {
        super(412, "PRECONDITION_FAILED", detail);
        this.policyId = null;
        this.currentRevision = currentRevision;
    }

    /**
     * A stale {@code If-Match} on an action catalogue entry (ADR-028). The entry is identified by
     * {@code (app, resourceType)} and has no policy id, so the {@code policyId} member is omitted
     * from the problem body rather than filled with something that is not one — the client still
     * gets {@code currentRevision}, which is all it needs to reload and retry.
     */
    public static PreconditionFailedException forCatalogueEntry(String app, String resourceType, long currentRevision) {
        return new PreconditionFailedException(
                "If-Match does not match the current revision " + currentRevision
                        + " of the action catalogue entry for resource type '" + resourceType + "' in app '" + app
                        + "'.",
                currentRevision);
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Precondition failed",
                getStatus(),
                getMessage(),
                policyId,
                currentRevision,
                null,
                null,
                null);
    }
}
