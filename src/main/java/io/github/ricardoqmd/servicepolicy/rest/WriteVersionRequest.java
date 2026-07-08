package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

public record WriteVersionRequest(Map<String, Object> content, String changeReason) {}
