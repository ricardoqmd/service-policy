package io.github.ricardoqmd.servicepolicy.rest;

import io.github.ricardoqmd.servicepolicy.rest.problem.ProblemException;

public class ForbiddenProblemException extends ProblemException {

    public ForbiddenProblemException(String detail) {
        super(403, "FORBIDDEN", detail);
    }
}
