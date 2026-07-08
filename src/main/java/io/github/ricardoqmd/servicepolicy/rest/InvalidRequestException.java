package io.github.ricardoqmd.servicepolicy.rest;

import io.github.ricardoqmd.servicepolicy.rest.problem.ProblemException;

public class InvalidRequestException extends ProblemException {

    public InvalidRequestException(String detail) {
        super(400, "BAD_REQUEST", detail);
    }
}
