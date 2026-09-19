package io.github.ricardoqmd.servicepolicy.controlplane;

import java.util.Set;

/**
 * The applications a caller may read, determined before a cross-application query is issued (ADR-033 §2).
 *
 * @param unrestricted {@code true} only for the bootstrap subject while the service is not installed.
 * @param apps         the readable applications when not unrestricted; possibly empty.
 */
public record ControlPlaneScope(boolean unrestricted, Set<String> apps) {

    public ControlPlaneScope {
        apps = Set.copyOf(apps);
    }

    static ControlPlaneScope unrestrictedScope() {
        return new ControlPlaneScope(true, Set.of());
    }

    static ControlPlaneScope of(Set<String> apps) {
        return new ControlPlaneScope(false, apps);
    }

    /** @return whether rows of {@code app} are visible in this scope. */
    public boolean includes(String app) {
        return unrestricted || apps.contains(app);
    }
}
