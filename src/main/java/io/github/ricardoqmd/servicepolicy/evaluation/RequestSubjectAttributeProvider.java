package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.Map;

import jakarta.inject.Singleton;

/**
 * Trivial {@link SubjectAttributeProvider} (ADR-010): subject attributes are whatever the caller
 * asserted in the request. The PDP performs no outbound lookup.
 *
 * <p>A missing bag yields an empty map — an absent attribute means a dependent rule does not match
 * (less privilege), never an error.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class RequestSubjectAttributeProvider implements SubjectAttributeProvider {

    @Override
    public Map<String, Object> attributesFor(String subjectId, Map<String, Object> fromRequest) {
        return fromRequest == null ? Map.of() : fromRequest;
    }
}
