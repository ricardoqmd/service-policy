package io.github.ricardoqmd.servicepolicy.rest;

/**
 * Optional request body for {@code POST /v1/apps/{app}/policies/{id}/deactivate}.
 *
 * @param subject optional declaration of on whose behalf the write is made (ADR-033 §4). It confers no
 *     authority; it changes only what the audit records.
 */
public record DeactivateRequest(String changeReason, String subject) {}
