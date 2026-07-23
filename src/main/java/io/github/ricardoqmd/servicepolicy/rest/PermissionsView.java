package io.github.ricardoqmd.servicepolicy.rest;

import java.util.List;

/**
 * Response of {@code GET /v1/apps/{app}/permissions} (ADR-030): the pairs the authenticated subject
 * can act on within the application, each flagged {@code conditional} and, when conditional, carrying
 * the resource attributes the client must supply to resolve it per instance.
 *
 * <p>A bare computed resource, not an enveloped collection: one subject, one app, one answer. It is
 * <strong>advisory</strong> — a hint for rendering, not an authorization decision. A UI that hides a
 * control it should have shown is a usability bug; a backend that skips {@code /evaluate} because this
 * said yes is a security bug (ADR-030 §2).
 *
 * @param permissions the included pairs, sorted by {@code (resourceType, action)}; a deterministic
 *                    deny and a pair with no applicable policy are omitted, not listed.
 * @param generatedAt RFC 3339 UTC instant the answer was computed; stable across a revalidation, and
 *                    not part of the ETag.
 */
public record PermissionsView(String app, String subject, List<PermissionEntry> permissions, String generatedAt) {}
