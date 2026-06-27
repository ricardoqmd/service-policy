package io.github.ricardoqmd.servicepolicy.persistence;

/** Thrown when a stored policy or condition document cannot be mapped to/from the domain model. */
public class PolicyDocumentException extends RuntimeException {

    public PolicyDocumentException(String message) {
        super(message);
    }
}
