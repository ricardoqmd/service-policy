package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

/**
 * Request body of {@code POST} and {@code PUT} on
 * {@code /v1/apps/&#123;app&#125;/configuration} (ADR-029). Both verbs take the same shape: create and
 * replace differ in preconditions, not in payload.
 *
 * <p>Typed rather than a free map, so bodies stay strict: any undeclared field — {@code app} above
 * all (ADR-026) — is rejected with 400 {@code BAD_REQUEST} by {@link UnknownPropertyMapper} instead
 * of being silently dropped.
 *
 * <p>Both sections are independently optional, but at least one must be present: a document that
 * configures nothing is indistinguishable from having none.
 *
 * @param subjectAttributes attribute name → claim path within the token.
 * @param pip               where to fetch the attributes the token does not carry.
 */
public record AppConfigWrite(Map<String, String> subjectAttributes, PipConfigWrite pip) {}
