package io.github.ricardoqmd.servicepolicy.persistence;

import io.github.ricardoqmd.servicepolicy.rest.problem.ProblemDetail;
import io.github.ricardoqmd.servicepolicy.rest.problem.ProblemException;

public class PolicyNotFoundException extends ProblemException {

    private final String policyId;

    public PolicyNotFoundException(String policyId) {
        super(404, "POLICY_NOT_FOUND", "No policy with id '" + policyId + "'.");
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
                null);
    }
}
