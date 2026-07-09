package io.github.ricardoqmd.servicepolicy.problem;

public class PolicyAlreadyExistsException extends ProblemException {

    private final String policyId;

    public PolicyAlreadyExistsException(String policyId) {
        super(409, "POLICY_ALREADY_EXISTS", "A policy with id '" + policyId + "' already exists.");
        this.policyId = policyId;
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Policy already exists",
                getStatus(),
                getMessage(),
                policyId,
                null,
                null,
                null);
    }
}
