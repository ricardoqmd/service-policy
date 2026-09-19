package io.github.ricardoqmd.servicepolicy.persistence;

/**
 * The installation marker as it is stored (ADR-033 §6, ADR-034), read in one query and classified before
 * anything relies on it.
 *
 * <p>The classification exists so that each way a marker can be unusable is reported as what it is: only a
 * marker in the shape an earlier build wrote "predates this build", and a marker of this build's shape that
 * lost its identifier, or one of a shape this build does not know, says so in its own words.
 *
 * @param status      what the stored marker is.
 * @param reservedApp the recorded identifier when {@code status} is {@link Status#RECORDED}; {@code null}
 *     otherwise.
 */
public record InstallationMarker(Status status, String reservedApp) {

    /** The possible readings of the stored marker. */
    public enum Status {
        /** There is no marker: the store has never been installed. */
        ABSENT,
        /** This build's shape with a non-blank string identifier: the only marker this build runs on. */
        RECORDED,
        /** The shape an earlier build wrote, which records no identifier. The store predates this build. */
        EARLIER_SHAPE,
        /** This build's shape, but its identifier is missing, blank or not a string. */
        NO_USABLE_IDENTIFIER,
        /** Any other schema marker, including none at all. */
        UNRECOGNISED_SHAPE
    }

    static InstallationMarker absent() {
        return new InstallationMarker(Status.ABSENT, null);
    }

    static InstallationMarker recorded(String reservedApp) {
        return new InstallationMarker(Status.RECORDED, reservedApp);
    }

    static InstallationMarker unusable(Status status) {
        return new InstallationMarker(status, null);
    }

    /** @return whether a marker exists, whatever its shape: installation mode is over either way. */
    public boolean installed() {
        return status != Status.ABSENT;
    }

    /** @return whether the marker is readable and records exactly {@code app}. */
    public boolean records(String app) {
        return status == Status.RECORDED && reservedApp.equals(app);
    }
}
