package io.github.ricardoqmd.servicepolicy.problem;

public class VersionNotFoundException extends ProblemException {

    private final String policyId;
    private final int requestedVersion;

    public VersionNotFoundException(String app, String policyId, int requestedVersion) {
        super(
                404,
                "VERSION_NOT_FOUND",
                "Policy '" + policyId + "' in app '" + app + "' has no version " + requestedVersion + ".");
        this.policyId = policyId;
        this.requestedVersion = requestedVersion;
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Version not found",
                getStatus(),
                getMessage(),
                policyId,
                null,
                requestedVersion,
                null);
    }
}
