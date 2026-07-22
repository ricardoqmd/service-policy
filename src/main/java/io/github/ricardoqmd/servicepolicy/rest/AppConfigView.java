package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response shape of an application's configuration (ADR-029).
 *
 * <p>A bare object, not an enveloped collection: configuration is a singleton per app, so there is
 * nothing to page or to wrap.
 *
 * <p>Unset sections are omitted from the JSON rather than emitted as empty objects, because "not
 * configured" and "configured empty" are different answers and only the first is representable —
 * validation rejects the second. As everywhere else, {@code app} IS returned: the server supplies it
 * from the path, and only a client-sent {@code app} could contradict the route (ADR-026).
 *
 * @param revision the document's current revision, mirrored in the response's strong ETag.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppConfigView(String app, Map<String, String> subjectAttributes, PipConfigView pip, long revision) {

    /** The {@code pip} section as returned; {@code credentialRef} is echoed as the reference it is. */
    public record PipConfigView(String url, int timeoutMs, int cacheTtlSeconds, String credentialRef) {}
}
