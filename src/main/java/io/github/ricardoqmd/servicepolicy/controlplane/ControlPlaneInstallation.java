package io.github.ricardoqmd.servicepolicy.controlplane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;

import org.jboss.logging.Logger;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.github.ricardoqmd.servicepolicy.domain.policy.AttributeRef;
import io.github.ricardoqmd.servicepolicy.domain.policy.CombiningAlgorithm;
import io.github.ricardoqmd.servicepolicy.domain.policy.Comparison;
import io.github.ricardoqmd.servicepolicy.domain.policy.Effect;
import io.github.ricardoqmd.servicepolicy.domain.policy.Operator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.Rule;
import io.github.ricardoqmd.servicepolicy.persistence.ActionCatalogueStore;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigDraft;
import io.github.ricardoqmd.servicepolicy.persistence.AppConfigStore;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.ForeignDocuments;
import io.github.ricardoqmd.servicepolicy.persistence.ForeignDocumentsQuery;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationMarker;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyAudit;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyHead;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyVersion;
import io.github.ricardoqmd.servicepolicy.persistence.SubjectProvenance;
import io.github.ricardoqmd.servicepolicy.problem.AppConfigAlreadyExistsException;
import io.github.ricardoqmd.servicepolicy.problem.CatalogueEntryAlreadyExistsException;
import io.github.ricardoqmd.servicepolicy.problem.PolicyAlreadyExistsException;
import io.github.ricardoqmd.servicepolicy.problem.PreconditionFailedException;
import io.quarkus.runtime.StartupEvent;

/**
 * Prepares the control plane at startup (ADR-033 §6): where its claim mapping comes from, and what a store
 * that has never been installed is seeded with.
 *
 * <p><strong>Refusals, not warnings.</strong> Each of these leaves an installation nobody can administer, or
 * one administered by something nobody chose, so the service does not start: a blank reserved identifier;
 * not installed and no claim path for {@code apps}; not installed and no bootstrap value; not installed and a
 * reserved application that already holds documents installation did not write; a marker that records a
 * different identifier, or none this build can read; an installed store whose reserved application holds no
 * usable {@code apps} mapping; and not installed and a seeding that did not reach its end state. Every refusal
 * but the last happens before anything is written; the last follows a seeding that wrote only installation's
 * own documents.
 *
 * <p><strong>Not installed.</strong> Installation seeds into an empty reserved application, or it does not
 * seed. The check reads the audit installation records on its own documents — all three fields — and never
 * what a document says: a document that merely resembles the baseline is still not one this installation
 * wrote. When something is in the way the refusal names it, kind and identifier, and states the recovery that
 * applies to what it found; nothing is adopted and nothing is deleted. Otherwise seeding converges, in the
 * reserved application, on its end state: the configuration with the configured mapping, the catalogue of the
 * control-plane resource type, and the baseline policy with an <em>active</em> version.
 *
 * <p><strong>Seeding finishes what it started (ADR-019).</strong> Its writes span several collections, so a
 * start that dies half-way leaves part of them, and the next start completes that part rather than skipping
 * it: what is missing decides what is written, never whether a document merely exists. Only documents that
 * carry installation's own audit are completed — the refusal above already ran, so completing is not
 * adopting. Over a complete installation it changes nothing. A concurrent start that wins a step is re-read,
 * not fought. Afterwards the end state is read back, and a start that did not reach it refuses: a convergence
 * that fails silently would let the bootstrap close installation over a control plane that grants nobody
 * anything. Seeding does <em>not</em> record the installation marker; the first successful control-plane
 * write accepted from the bootstrap subject does.
 *
 * <p><strong>Installed.</strong> The reserved identifier is read back from the marker: a configured
 * identifier that differs from the recorded one refuses to start, and so does a marker this build cannot read
 * an identifier from, each in its own words. Neither is guessed and neither is backfilled. The stored
 * configuration is then the only source of the mapping and the claim-path property is ignored; if the
 * property differs from a usable stored claim path, one warning says so and names the property — never a
 * claim path or any token material — and nothing is written. A stored value that is not a usable claim path
 * is a refusal, so the warning cannot describe it.
 *
 * <p>Runs after the index observers, which have the default priority: seeding relies on the unique indexes
 * to turn a concurrent duplicate into a no-op.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class ControlPlaneInstallation {

    private static final Logger log = Logger.getLogger(ControlPlaneInstallation.class);

    static final String APPS_PROPERTY = "service-policy.control-plane.subject-attributes.apps";
    static final String BOOTSTRAP_VALUE_PROPERTY = "service-policy.control-plane.bootstrap.value";
    static final String RESERVED_APP_PROPERTY = "service-policy.control-plane.reserved-app";

    /** The id of the seeded policy in the reserved application. */
    public static final String BASELINE_POLICY_ID = "control-plane-baseline";

    /** How many foreign documents a refusal names before it only counts the rest. */
    static final int NAMED_OBSTRUCTIONS = 10;

    private static final String SEED_REASON = "installation: baseline control-plane policy (ADR-033)";

    /** The two ways out of an installed store whose control-plane mapping is lost; the API cannot repair it. */
    private static final String MAPPING_RECOVERY = " Every control-plane decision reads that mapping, so the"
            + " deployment would refuse every caller, and no call could repair it: the repair would itself be"
            + " decided with the mapping that is missing. Recover by writing that application's configuration in"
            + " the store directly — its app, subjectAttributes." + ControlPlaneAuthorizer.APPS + " set to a"
            + " non-empty claim path, schemaVersion 1 and revision as a 64-bit integer, so that the API can update"
            + " it afterwards — or by installing a new store";

    private final ServicePolicyConfig cfg;
    private final InstallationStore installationStore;
    private final AppConfigStore configStore;
    private final ActionCatalogueStore catalogueStore;
    private final PolicyLifecycleStore lifecycleStore;
    private final ForeignDocumentsQuery foreignDocuments;

    ControlPlaneInstallation(
            ServicePolicyConfig cfg,
            InstallationStore installationStore,
            AppConfigStore configStore,
            ActionCatalogueStore catalogueStore,
            PolicyLifecycleStore lifecycleStore,
            ForeignDocumentsQuery foreignDocuments) {
        this.cfg = cfg;
        this.installationStore = installationStore;
        this.configStore = configStore;
        this.catalogueStore = catalogueStore;
        this.lifecycleStore = lifecycleStore;
        this.foreignDocuments = foreignDocuments;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 1000) StartupEvent event) {
        prepare();
    }

    /**
     * Validates, then seeds or reports, as described on the class.
     *
     * @throws IllegalStateException on any of the refusals the class describes — at startup, this refuses to
     *     start the service, having written nothing. The message names the property at fault, the recorded
     *     identifier where the fault is a disagreement with it, and the foreign documents by kind and
     *     identifier where they are the fault; it never names a claim path's value, a document's contents or
     *     any token material.
     */
    public void prepare() {
        ServicePolicyConfig.ControlPlane controlPlane = cfg.controlPlane();
        String reservedApp = controlPlane.reservedApp();
        if (reservedApp == null || reservedApp.isBlank()) {
            throw new IllegalStateException(RESERVED_APP_PROPERTY
                    + " is blank: it names the application that holds the control-plane rules and is recorded at"
                    + " installation, so it must name one");
        }
        Optional<String> appsClaim = controlPlane.subjectAttributes().apps().filter(value -> !value.isBlank());

        InstallationMarker marker = installationStore.marker();
        if (marker.installed()) {
            verifyReservedApp(marker, reservedApp);
            reportDivergence(storedMapping(reservedApp), appsClaim);
            return;
        }
        if (appsClaim.isEmpty()) {
            throw new IllegalStateException(APPS_PROPERTY
                    + " must be set while the service is not installed: it is the claim path that carries the"
                    + " caller's applications, and without it no caller could ever be authorized");
        }
        if (controlPlane.bootstrap().value().filter(value -> !value.isBlank()).isEmpty()) {
            throw new IllegalStateException(BOOTSTRAP_VALUE_PROPERTY
                    + " must be set while the service is not installed: it names the one caller installation"
                    + " mode accepts, and without it no caller could perform the write that closes it");
        }
        refuseForeignDocuments(reservedApp);
        converge(reservedApp, appsClaim.get());
    }

    /**
     * Seeds what is missing of installation's end state, then verifies that the end state holds (ADR-033 §6,
     * ADR-019). Called once the foreign-document refusal has passed.
     *
     * @throws IllegalStateException when the end state does not hold after converging.
     */
    void converge(String reservedApp, String appsClaim) {
        seed(reservedApp, appsClaim);
        verifyEndState(reservedApp);
    }

    /**
     * The reserved identifier is part of the installation (ADR-033 §6): it is compared with the one the
     * marker recorded, and a disagreement — or a marker this build cannot read one from — stops the service.
     * Each unreadable marker is described as what it is; only the earlier shape predates this build.
     */
    private static void verifyReservedApp(InstallationMarker marker, String reservedApp) {
        String neitherGuessed = " The identifier is part of the installation (ADR-033 §6) and is neither guessed"
                + " nor backfilled, because the wrong guess hands the control plane to whichever policies live in"
                + " another application";
        switch (marker.status()) {
            case EARLIER_SHAPE ->
                throw new IllegalStateException(
                        "this store carries an installation marker in the shape an earlier build wrote, which records"
                                + " no reserved control-plane application: it predates this build and must be"
                                + " reinstalled." + neitherGuessed);
            case NO_USABLE_IDENTIFIER ->
                throw new IllegalStateException(
                        "this store carries an installation marker of this build's shape whose reserved control-plane"
                                + " application is missing, blank or not a string. This build never writes such a"
                                + " marker, so it was changed outside the service: restore the identifier it was"
                                + " installed with in the store, or install a new store." + neitherGuessed);
            case UNRECOGNISED_SHAPE ->
                throw new IllegalStateException(
                        "this store carries an installation marker whose schemaVersion is not one this build reads"
                                + " (ADR-034): it was written by a different build or changed outside the service,"
                                + " and it is neither read nor rewritten. Run the build that wrote it, or install a"
                                + " new store." + neitherGuessed);
            case RECORDED -> {
                if (!marker.records(reservedApp)) {
                    throw new IllegalStateException(RESERVED_APP_PROPERTY + " is \"" + reservedApp
                            + "\", but this store was installed with \"" + marker.reservedApp()
                            + "\": the reserved application is fixed at installation (ADR-033 §6) and"
                            + " configuration cannot change it. Set the property to \"" + marker.reservedApp()
                            + "\", or install a new store");
                }
            }
            case ABSENT -> throw new IllegalStateException("no installation marker to verify");
        }
    }

    /**
     * The claim path stored for the reserved application, which every control-plane decision applies.
     *
     * @throws IllegalStateException when nothing usable is stored — nothing at all, or a value that is not a
     *     non-empty string and so cannot resolve a claim path: either way every caller is denied, so there
     *     would be nobody left to repair it.
     */
    private String storedMapping(String reservedApp) {
        Optional<Object> stored = configStore.storedMappingValue(reservedApp, ControlPlaneAuthorizer.APPS);
        if (stored.isEmpty()) {
            throw new IllegalStateException("this store is installed, but its reserved control-plane application"
                    + " holds no claim mapping for " + ControlPlaneAuthorizer.APPS + "." + MAPPING_RECOVERY);
        }
        if (!(stored.get() instanceof String claimPath) || claimPath.isBlank()) {
            throw new IllegalStateException("this store is installed, but the claim mapping its reserved"
                    + " control-plane application stores for " + ControlPlaneAuthorizer.APPS
                    + " is not a usable claim path: a claim path is a non-empty string, and what is stored"
                    + " cannot resolve one." + MAPPING_RECOVERY);
        }
        return claimPath;
    }

    /**
     * Installation seeds into an empty reserved application, or it does not seed (ADR-033 §6). The refusal
     * names what is in the way and the recovery that applies to it: a policy cannot be removed through the
     * API, so where one is in the way the only recovery is a reserved identifier that does not exist yet.
     */
    private void refuseForeignDocuments(String reservedApp) {
        ForeignDocuments found = foreignDocuments.in(reservedApp);
        if (found.none()) {
            return;
        }
        List<String> named = new ArrayList<>();
        if (found.configuration()) {
            named.add("the configuration");
        }
        found.catalogueEntries().forEach(resourceType -> named.add("catalogue entry \"" + resourceType + "\""));
        found.policies().forEach(policyId -> named.add("policy \"" + policyId + "\""));
        String listed = String.join("; ", named.subList(0, Math.min(NAMED_OBSTRUCTIONS, named.size())));
        if (named.size() > NAMED_OBSTRUCTIONS) {
            listed += "; and " + (named.size() - NAMED_OBSTRUCTIONS) + " more";
        }

        String elsewhere = "set " + RESERVED_APP_PROPERTY + " to an application that does not exist yet";
        String recovery = found.policies().isEmpty()
                ? " Either delete them with the version that wrote them, through its API, before upgrading, or "
                        + elsewhere + "."
                : " A policy cannot be removed through the API — its versions are immutable, and deactivating it"
                        + " is not enough, because this check does not read what a document says — so the way"
                        + " out is to " + elsewhere + ".";
        throw new IllegalStateException(RESERVED_APP_PROPERTY + " is \"" + reservedApp
                + "\", and that application already holds documents this installation did not write: " + listed
                + ". Installation seeds into an empty reserved application or not at all (ADR-033 §6), because"
                + " adopting them would let whoever wrote them decide who administers every application. Nothing"
                + " was written." + recovery);
    }

    private void seed(String reservedApp, String appsClaim) {
        AuditActor installer = AuditActor.installation();

        if (configStore.find(reservedApp).isEmpty()) {
            try {
                configStore.create(
                        reservedApp,
                        new AppConfigDraft(Map.of(ControlPlaneAuthorizer.APPS, appsClaim), null),
                        installer);
            } catch (AppConfigAlreadyExistsException concurrent) {
                // created in the meantime: left as it is
            }
        }

        if (catalogueStore.find(reservedApp, ControlPlaneAction.RESOURCE_TYPE).isEmpty()) {
            try {
                catalogueStore.create(reservedApp, ControlPlaneAction.RESOURCE_TYPE, verbs(), installer);
            } catch (CatalogueEntryAlreadyExistsException concurrent) {
                // created in the meantime: left as it is
            }
        }

        convergeBaseline(reservedApp, installer);
    }

    /**
     * The baseline, decided by what is missing (ADR-019): no version 1 → create, which inserts it — the commit
     * point of ADR-019 §1, which also completes a head left without one; version 1 and no active version →
     * activate it; already active → nothing. A head or version installation did not write is never a step to
     * complete: it is left as it is, and {@link #verifyEndState} refuses.
     */
    private void convergeBaseline(String reservedApp, AuditActor installer) {
        Optional<PolicyHead> head = lifecycleStore.findHead(reservedApp, BASELINE_POLICY_ID);
        if (head.isPresent()
                && (head.get().activeVersion() != null
                        || !writtenByInstallation(head.get().audit()))) {
            return;
        }
        if (lifecycleStore.findVersion(reservedApp, BASELINE_POLICY_ID, 1).isEmpty()) {
            try {
                lifecycleStore.create(reservedApp, baselinePolicy(), installer, SEED_REASON);
            } catch (PolicyAlreadyExistsException concurrent) {
                // version 1 was inserted in the meantime: re-read below
            }
            head = lifecycleStore.findHead(reservedApp, BASELINE_POLICY_ID);
        }
        Optional<PolicyVersion> first = lifecycleStore.findVersion(reservedApp, BASELINE_POLICY_ID, 1);
        if (head.isEmpty()
                || head.get().activeVersion() != null
                || first.isEmpty()
                || !writtenByInstallation(first.get().audit())) {
            return;
        }
        try {
            lifecycleStore.activate(
                    reservedApp, BASELINE_POLICY_ID, 1, head.get().revision(), installer, SEED_REASON);
        } catch (PreconditionFailedException concurrent) {
            // the head moved in the meantime, another start activating it: verifyEndState reads the outcome
        }
    }

    /**
     * Reads the end state back: a usable {@code apps} claim path in the reserved configuration, the catalogue
     * entry of the control-plane resource type, and the baseline active as installation activated it.
     *
     * @throws IllegalStateException naming what does not hold; nothing further is written.
     */
    private void verifyEndState(String reservedApp) {
        List<String> missing = new ArrayList<>();
        Object mapping = configStore
                .storedMappingValue(reservedApp, ControlPlaneAuthorizer.APPS)
                .orElse(null);
        if (!(mapping instanceof String claimPath) || claimPath.isBlank()) {
            missing.add("a configuration whose " + ControlPlaneAuthorizer.APPS + " mapping is a usable claim path");
        }
        if (catalogueStore.find(reservedApp, ControlPlaneAction.RESOURCE_TYPE).isEmpty()) {
            missing.add("the catalogue entry \"" + ControlPlaneAction.RESOURCE_TYPE + "\"");
        }
        Optional<PolicyHead> baseline = lifecycleStore.findHead(reservedApp, BASELINE_POLICY_ID);
        if (baseline.isEmpty()
                || baseline.get().activeVersion() == null
                || !writtenByInstallation(baseline.get().audit())) {
            missing.add("the policy \"" + BASELINE_POLICY_ID + "\", activated by installation");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(RESERVED_APP_PROPERTY + " is \"" + reservedApp
                    + "\", and installation could not reach its end state there: missing " + String.join("; ", missing)
                    + ". Startup completes what an earlier start left half-written (ADR-019) and then checks the"
                    + " result; it does not start without it, because closing installation over this control plane"
                    + " would leave nobody able to administer anything. Restart to retry; if it still refuses, set "
                    + RESERVED_APP_PROPERTY + " to an application that does not exist yet");
        }
    }

    /** All three audit fields installation records on its own documents (ADR-033 §4). */
    private static boolean writtenByInstallation(PolicyAudit audit) {
        return audit != null
                && AuditActor.INSTALLATION.equals(audit.createdBy())
                && AuditActor.INSTALLATION.equals(audit.subject())
                && audit.subjectProvenance() == SubjectProvenance.VERIFIED;
    }

    /**
     * One warning, and only where the service still works: a configured claim path that differs from a usable
     * stored one. {@code stored} is always a non-empty claim path here — {@link #storedMapping} refuses
     * anything else first — so the warning never presents an unusable mapping as merely different.
     */
    private void reportDivergence(String stored, Optional<String> appsClaim) {
        if (appsClaim.isEmpty()) {
            return;
        }
        if (!Objects.equals(stored, appsClaim.get())) {
            log.warnf(
                    "%s differs from the claim path stored in the reserved control-plane application's"
                            + " configuration; the stored claim path is in force and the property is ignored",
                    APPS_PROPERTY);
        }
    }

    private static List<String> verbs() {
        return List.of(
                ControlPlaneAction.READ.verb(),
                ControlPlaneAction.WRITE.verb(),
                ControlPlaneAction.ACTIVATE.verb(),
                ControlPlaneAction.DEACTIVATE.verb());
    }

    /** {@code permit when resource.attr.app IN subject.attr.apps} — exactly that, and nothing else (§1). */
    static Policy baselinePolicy() {
        return new Policy(
                BASELINE_POLICY_ID,
                1,
                ControlPlaneAction.RESOURCE_TYPE,
                verbs(),
                CombiningAlgorithm.DENY_OVERRIDES,
                Effect.DENY,
                List.of(new Rule(
                        "app-in-subject-apps",
                        Effect.PERMIT,
                        new Comparison(
                                Operator.IN,
                                new AttributeRef("resource.attr." + ControlPlaneAuthorizer.APP),
                                new AttributeRef("subject.attr." + ControlPlaneAuthorizer.APPS)))));
    }
}
