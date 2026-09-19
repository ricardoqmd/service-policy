package io.github.ricardoqmd.servicepolicy.rest;

/**
 * Request body for {@code POST /v1/apps/{app}/policies/{id}/activate}.
 *
 * @param subject optional declaration of on whose behalf the write is made (ADR-033 §4). It confers no
 *     authority; it changes only what the audit records.
 */
public record ActivateRequest(Integer version, String changeReason, String subject) {}
