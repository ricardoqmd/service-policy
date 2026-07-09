package io.github.ricardoqmd.servicepolicy.problem;

public class ForbiddenProblemException extends ProblemException {

    public ForbiddenProblemException(String detail) {
        super(403, "FORBIDDEN", detail);
    }
}
