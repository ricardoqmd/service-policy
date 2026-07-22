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
     * A stale {@code If-Match} on a resource whose identity is entirely in its request path — an
     * action catalogue entry (ADR-028), an application's configuration (ADR-029). Such a resource has
     * no policy id, so the {@code policyId} member is omitted from the problem body rather than
     * filled with something that is not one; the client still gets {@code currentRevision}, which is
     * all it needs to reload and retry.
     *
     * @param resourceDescription names the target as it reads inside "…the current revision N of
     *     <em>&lt;description&gt;</em>." — e.g. "the configuration of app 'nami'".
     */
    private static PreconditionFailedException forPathAddressedResource(
            String resourceDescription, long currentRevision) {
        return new PreconditionFailedException(
                "If-Match does not match the current revision " + currentRevision + " of " + resourceDescription + ".",
                currentRevision);
    }

    /** Stale {@code If-Match} on the action catalogue entry of {@code (app, resourceType)} (ADR-028). */
    public static PreconditionFailedException forCatalogueEntry(String app, String resourceType, long currentRevision) {
        return forPathAddressedResource(
                "the action catalogue entry for resource type '" + resourceType + "' in app '" + app + "'",
                currentRevision);
    }

    /** Stale {@code If-Match} on an application's configuration document (ADR-029). */
    public static PreconditionFailedException forAppConfiguration(String app, long currentRevision) {
        return forPathAddressedResource("the configuration of app '" + app + "'", currentRevision);
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
