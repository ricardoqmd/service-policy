package io.github.ricardoqmd.servicepolicy.persistence;

import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;

/**
 * Read model for a policy head (ADR-016), decoupled from the persistence document and from the REST
 * wire shape. {@code activeContent} is the domain policy at {@code activeVersion}, or {@code null}
 * when the policy has no active version.
 */
public record PolicyHead(
        String policyId,
        String app,
        String resourceType,
        Integer activeVersion,
        long revision,
        PolicyAudit audit,
        Policy activeContent) {}
