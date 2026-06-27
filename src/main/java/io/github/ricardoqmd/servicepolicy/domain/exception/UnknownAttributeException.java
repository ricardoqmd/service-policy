package io.github.ricardoqmd.servicepolicy.domain.exception;

/**
 * Thrown when a policy references an attribute path the engine does not resolve.
 *
 * <p>Surfacing this loudly (rather than treating an unknown path as {@code null}) keeps
 * misconfigured policies from silently producing wrong decisions.
 */
public class UnknownAttributeException extends RuntimeException {

    public UnknownAttributeException(String path) {
        super("Unknown attribute path: " + path);
    }
}
