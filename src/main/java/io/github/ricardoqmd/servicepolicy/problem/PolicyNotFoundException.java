package io.github.ricardoqmd.servicepolicy.problem;

public class PolicyNotFoundException extends ProblemException {

    private final String policyId;

    public PolicyNotFoundException(String app, String policyId) {
        super(404, "POLICY_NOT_FOUND", "No policy with id '" + policyId + "' in app '" + app + "'.");
        this.policyId = policyId;
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Policy not found",
                getStatus(),
                getMessage(),
                policyId,
                null,
                null,
                null,
                null);
    }
}
