package io.github.ricardoqmd.servicepolicy.rest;

/** Request body for {@code POST /v1/policies/{id}/activate}. */
public record ActivateRequest(Integer version, String changeReason) {}
