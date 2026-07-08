package io.github.ricardoqmd.servicepolicy.rest;

import io.github.ricardoqmd.servicepolicy.rest.problem.ProblemDetail;
import io.github.ricardoqmd.servicepolicy.rest.problem.ProblemException;

public class PreconditionFailedException extends ProblemException {

    private final String policyId;
    private final long currentRevision;

    public PreconditionFailedException(String policyId, long currentRevision) {
        super(412, "PRECONDITION_FAILED", "If-Match does not match the current revision " + currentRevision + ".");
        this.policyId = policyId;
        this.currentRevision = currentRevision;
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
                null);
    }
}
