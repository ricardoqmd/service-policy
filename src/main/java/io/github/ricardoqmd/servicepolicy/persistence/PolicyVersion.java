package io.github.ricardoqmd.servicepolicy.persistence;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;

/**
 * Read model for an immutable policy version (ADR-016): its domain {@link Policy} content plus the
 * audit metadata of that version.
 */
public record PolicyVersion(Policy content, PolicyAudit audit) {}
