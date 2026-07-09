package io.github.ricardoqmd.servicepolicy.problem;

import java.util.List;

public class PolicyValidationException extends ProblemException {

    private final List<ProblemDetail.InvalidParam> invalidParams;

    public PolicyValidationException(List<ProblemDetail.InvalidParam> invalidParams) {
        super(400, "INVALID_POLICY", "The policy document failed validation.");
        this.invalidParams = List.copyOf(invalidParams);
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Invalid policy document",
                getStatus(),
                getMessage(),
                null,
                null,
                null,
                invalidParams);
    }
}
