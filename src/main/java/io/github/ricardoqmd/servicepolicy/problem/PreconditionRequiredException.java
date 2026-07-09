package io.github.ricardoqmd.servicepolicy.problem;

public class PreconditionRequiredException extends ProblemException {

    public PreconditionRequiredException() {
        super(428, "PRECONDITION_REQUIRED", "This write requires an If-Match header carrying the current ETag.");
    }
}
