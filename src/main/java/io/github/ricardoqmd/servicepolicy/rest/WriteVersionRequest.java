package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

/**
 * Request body for appending a version.
 *
 * @param subject optional declaration of on whose behalf the write is made (ADR-033 §4). It confers no
 *     authority; it changes only what the audit records.
 */
public record WriteVersionRequest(Map<String, Object> content, String changeReason, String subject) {}
