package io.github.ricardoqmd.servicepolicy.enumeration;

import java.util.Map;

/**
 * The inputs a three-valued evaluation has without a resource instance (ADR-030 §4): the caller's
 * identity, the subject attributes derived for the application (ADR-029), and the resource
 * <em>type</em> of the pair being enumerated. There is no resource id, no resource attributes and no
 * request context — their absence is the whole reason a decision may come back INDETERMINATE.
 *
 * @param subjectId          the {@code sub} of the validated token (ADR-013).
 * @param subjectAttributes  attribute name → value, derived from the token's mapped claims; an
 *                           attribute the map does not contain is one the caller <em>might</em>
 *                           assert at {@code /evaluate} (ADR-010), so it resolves to INDETERMINATE
 *                           rather than to absent.
 * @param resourceType       the resource type of the pair; resolves {@code resource.type} references.
 */
public record EnumerationContext(String subjectId, Map<String, Object> subjectAttributes, String resourceType) {

    public EnumerationContext {
        subjectAttributes = Map.copyOf(subjectAttributes);
    }
}
