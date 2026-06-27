package io.github.ricardoqmd.servicepolicy.domain.policy;

/**
 * Algorithm that resolves multiple applicable effects into one.
 *
 * <p>Only {@link #DENY_OVERRIDES} (fail-safe) is supported in the MVP; additional
 * algorithms are added when a policy actually needs them (see ADR-009).
 */
public enum CombiningAlgorithm {
    DENY_OVERRIDES
}
