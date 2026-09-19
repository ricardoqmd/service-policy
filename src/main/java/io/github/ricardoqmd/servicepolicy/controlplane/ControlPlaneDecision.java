package io.github.ricardoqmd.servicepolicy.controlplane;

/** The outcome of one control-plane decision (ADR-033). */
public enum ControlPlaneDecision {
    /** Permitted by the control-plane policy set of an installed service. */
    PERMITTED,
    /**
     * Permitted because the service is not installed and the caller is the bootstrap subject. The first
     * such write that succeeds records the installation marker, after which this outcome never occurs again.
     */
    PERMITTED_AS_BOOTSTRAP,
    DENIED;

    public boolean permitted() {
        return this != DENIED;
    }
}
