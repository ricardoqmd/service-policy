package io.github.ricardoqmd.servicepolicy.domain.exception;

/** Thrown when operand types are incompatible with the requested operator. */
public class PolicyTypeException extends RuntimeException {

    public PolicyTypeException(String message) {
        super(message);
    }
}
