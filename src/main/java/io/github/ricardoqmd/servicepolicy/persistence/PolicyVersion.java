package io.github.ricardoqmd.servicepolicy.persistence;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;

/**
 * Read model for an immutable policy version (ADR-016): its domain {@link Policy} content plus the
 * audit metadata of that version.
 *
 * <p>{@code app} is carried here rather than inside {@code content}, because it is part of the
 * policy's identity, not of its content (ADR-026). Read views render it.
 */
public record PolicyVersion(String app, Policy content, PolicyAudit audit) {}
