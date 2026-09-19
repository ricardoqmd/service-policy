package io.github.ricardoqmd.servicepolicy;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneInstallation;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigProvider;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationRepository;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationStore;

/**
 * Arrange-phase helper for the control plane (ADR-033).
 *
 * <p>Every control-plane endpoint is authorized by policy, so a test that exercises one needs an installed
 * store — the reserved application's configuration, catalogue and baseline policy, and the installation
 * marker — and a caller whose token carries the applications it administers in the {@value #APPS_CLAIM}
 * claim. {@link #installed} provides the first through the production seeding path, and {@link #TEST_APPS}
 * is the claim value the suites use for the second.
 *
 * <p>Suites wipe the collections they write to, the reserved application's documents included, so
 * {@link #installed} is called at the end of their arrange step, after the wipe. It is idempotent.
 */
@Singleton
public class ControlPlaneTestSupport {

    /** The claim path the test profile maps to {@code subject.attr.apps}. */
    public static final String APPS_CLAIM = "apps";

    /** The bootstrap claim value of the test profile. */
    public static final String BOOTSTRAP_SUBJECT = "bootstrap-admin";

    /** The reserved application's default identifier. */
    public static final String RESERVED_APP = "service-policy-control-plane";

    /**
     * Every application the existing suites administer, as a JSON array claim value. A suite that names an
     * application of its own declares it there, beside the case that needs it, rather than here.
     */
    public static final String TEST_APPS = "[\"test-app\",\"other-app\",\"app-a\",\"app-b\",\"app-x\",\"app-z\","
            + "\"schema-api\",\"schema-app\",\"cross-app\"]";

    @Inject
    public ControlPlaneInstallation installation;

    @Inject
    InstallationStore installationStore;

    @Inject
    public InstallationRepository installationRepository;

    @Inject
    AppConfigProvider configProvider;

    /** An installed control plane: seeded exactly as a fresh deployment is, then closed by the marker. */
    public void installed() {
        notInstalled();
        installationStore.recordInstalled("test-installer", RESERVED_APP);
    }

    /**
     * A store that has never been installed: no marker, and the reserved application seeded as startup seeds
     * it. Removing the marker is something only a test does — no production code path can.
     */
    public void notInstalled() {
        installationRepository.deleteAll();
        configProvider.invalidate(RESERVED_APP);
        installation.prepare();
    }
}
