package io.github.ricardoqmd.servicepolicy.rest;

/** Optional request body for {@code POST /v1/policies/{id}/deactivate}. */
public record DeactivateRequest(String changeReason) {}
