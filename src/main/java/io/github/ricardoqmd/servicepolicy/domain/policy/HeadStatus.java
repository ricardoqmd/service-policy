package io.github.ricardoqmd.servicepolicy.domain.policy;

import java.util.Locale;
import java.util.Optional;

/**
 * Lifecycle state of a policy head, as seen by the administrative listing surface (ADR-025).
 *
 * <p>A head is {@code ACTIVE} when it points at a version ({@code activeVersion != null}) and
 * {@code INACTIVE} when it does not — the structural invariant of ADR-016. {@code ALL} is the
 * absence of a state filter, not a third state of a head.
 *
 * <p>This is vocabulary of the model, not of a layer: the web layer receives it as a query
 * parameter and the persistence layer turns it into a filter, so it lives in the domain and neither
 * layer has to depend on the other.
 */
public enum HeadStatus {
    /** Heads that point at a version and are therefore evaluable. */
    ACTIVE,
    /** Heads with no active version — created but not yet activated, or deactivated (ADR-014). */
    INACTIVE,
    /** No state filter: every head, whatever its lifecycle state. */
    ALL;

    /**
     * Parses the external (case-insensitive) representation of a status.
     *
     * @return the matching status, or empty if the value names none — the caller decides how to
     *     reject it.
     */
    public static Optional<HeadStatus> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (HeadStatus status : values()) {
            if (status.name().equals(value.trim().toUpperCase(Locale.ROOT))) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
