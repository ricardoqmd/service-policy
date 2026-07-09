package io.github.ricardoqmd.servicepolicy.problem;

public class InvalidRequestException extends ProblemException {

    public InvalidRequestException(String detail) {
        super(400, "BAD_REQUEST", detail);
    }
}
