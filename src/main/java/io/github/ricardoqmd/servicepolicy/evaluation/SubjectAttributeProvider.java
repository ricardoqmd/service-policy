package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.Map;

/**
 * Port: resolves the non-identity attributes of a subject for an evaluation.
 *
 * <p>Per ADR-010, the MVP adapter simply returns the attributes the caller (PEP) asserted in the
 * request. The seam exists so external attribute enrichment (an outbound PIP) can be introduced
 * later as an adapter swap, without touching the engine. Identity is never resolved here — it
 * comes from the validated JWT.
 */
public interface SubjectAttributeProvider {

    /**
     * @param subjectId   the JWT-resolved subject id.
     * @param fromRequest attributes asserted by the caller in the request; may be {@code null}.
     * @return the subject attribute bag to evaluate against; never {@code null}.
     */
    Map<String, Object> attributesFor(String subjectId, Map<String, Object> fromRequest);
}
