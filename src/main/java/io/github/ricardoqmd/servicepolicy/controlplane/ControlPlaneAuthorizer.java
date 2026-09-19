package io.github.ricardoqmd.servicepolicy.controlplane;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Singleton;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import io.github.ricardoqmd.servicepolicy.config.ServicePolicyConfig;
import io.github.ricardoqmd.servicepolicy.domain.model.AuthorizationRequest;
import io.github.ricardoqmd.servicepolicy.domain.model.Resource;
import io.github.ricardoqmd.servicepolicy.domain.model.Subject;
import io.github.ricardoqmd.servicepolicy.domain.policy.ConditionEvaluator;
import io.github.ricardoqmd.servicepolicy.domain.policy.Policy;
import io.github.ricardoqmd.servicepolicy.domain.policy.PolicyEngine;
import io.github.ricardoqmd.servicepolicy.domain.policy.PolicySelector;
import io.github.ricardoqmd.servicepolicy.enumeration.SubjectAttributeDeriver;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationMarker;
import io.github.ricardoqmd.servicepolicy.persistence.InstallationStore;
import io.github.ricardoqmd.servicepolicy.persistence.PolicyLifecycleStore;

/**
 * Decides whether a caller may perform a control-plane action on an application (ADR-033).
 *
 * <p><strong>Where the subject's attributes come from — the property everything rests on (§3).</strong>
 * The subject of a control-plane decision is the caller itself, and its attributes are derived from its
 * validated token and from nothing else. The inputs of {@link #decide} are therefore the application from
 * the route, the action, and the caller's validated identity: there is no parameter through which a
 * subject attribute could be passed, so no call site can pass one. The caller-asserted channel of ADR-010
 * belongs to {@code /evaluate}, whose input type carries a bag; that type is deliberately not used here.
 *
 * <p><strong>Which mapping.</strong> The claim mapping applied is the one stored in the configuration of the
 * reserved control-plane application, never the configuration of the application being decided about: that
 * application may have no configuration yet — creating one is itself a control-plane write — and the merged
 * catalogue names no application at all.
 *
 * <p><strong>Where the rule is kept, and what it decides about.</strong> The policy set is read from the
 * reserved application (§6). The application being decided about travels as {@code resource.attr.app},
 * taken from the route by the caller of {@link #decide}, and is never parsed out of a path here.
 *
 * <p><strong>Installation mode.</strong> While the store carries no installation marker, the policy set is
 * not consulted: the bootstrap subject is permitted and everyone else is denied. Once the marker exists the
 * bootstrap claim value grants nothing, whether or not it is still configured.
 *
 * <p><strong>The reserved identifier binds while the service runs, not only when it starts (§6).</strong>
 * Each decision reads the marker once — the read that tells whether installation mode is over — and in the
 * same read compares the identifier it recorded with the configured one. When they disagree, or the marker
 * records none this build can read, every control-plane call is denied and the merged catalogue is empty.
 * That covers an instance started before installation closed, under a different identifier, which would
 * otherwise go on deciding from another application's policies until it restarts. The disagreement is
 * logged once each time it begins — when it is first seen, and again whenever it returns after agreement —
 * never per request.
 *
 * <p><strong>The installation identity is installation's alone.</strong> A caller whose resolved subject is
 * {@link AuditActor#INSTALLATION} is denied every control-plane call and reads an empty merged catalogue,
 * whatever its token carries, so no caller can write a document carrying installation's audit marks.
 */
// @Singleton (not @ApplicationScoped): stateless bean, no proxy needed (see ADR-009).
@Singleton
public class ControlPlaneAuthorizer {

    /** The subject attribute that carries the caller's applications (ADR-033 §1). */
    public static final String APPS = "apps";

    /** The resource attribute that carries the application being decided about (ADR-033 §1). */
    public static final String APP = "app";

    private static final String BOOTSTRAP = "bootstrap";

    private static final String RESERVED_APP_PROPERTY = "service-policy.control-plane.reserved-app";

    private static final Logger log = Logger.getLogger(ControlPlaneAuthorizer.class);

    private final InstallationStore installationStore;
    private final PolicyLifecycleStore lifecycleStore;
    private final SubjectAttributeDeriver deriver;
    private final ServicePolicyConfig cfg;
    private final PolicySelector selector = new PolicySelector();
    private final PolicyEngine engine = new PolicyEngine(new ConditionEvaluator());
    private final AtomicReference<String> reportedDisagreement = new AtomicReference<>();

    ControlPlaneAuthorizer(
            InstallationStore installationStore,
            PolicyLifecycleStore lifecycleStore,
            SubjectAttributeDeriver deriver,
            ServicePolicyConfig cfg) {
        this.installationStore = installationStore;
        this.lifecycleStore = lifecycleStore;
        this.deriver = deriver;
        this.cfg = cfg;
    }

    /**
     * @param app           the application being decided about, exactly as route matching produced it.
     * @param action        the control-plane action.
     * @param callerSubject the caller's identity, resolved from its validated token.
     * @param token         the caller's validated token, or {@code null} when the principal carries none —
     *                      which derives no attributes and can therefore permit nothing.
     */
    public ControlPlaneDecision decide(
            String app, ControlPlaneAction action, String callerSubject, JsonWebToken token) {
        if (isInstallationIdentity(callerSubject)) {
            return ControlPlaneDecision.DENIED;
        }
        InstallationMarker marker = installationStore.marker();
        if (!marker.installed()) {
            return isBootstrap(token) ? ControlPlaneDecision.PERMITTED_AS_BOOTSTRAP : ControlPlaneDecision.DENIED;
        }
        if (!boundToThisDeployment(marker)) {
            return ControlPlaneDecision.DENIED;
        }
        return permits(app, action, callerSubject, subjectAttributes(token))
                ? ControlPlaneDecision.PERMITTED
                : ControlPlaneDecision.DENIED;
    }

    /**
     * The applications whose policies the caller may read, for a view that spans applications (ADR-033 §2).
     *
     * <p>The candidates are the caller's {@code subject.attr.apps}, resolved exactly as {@link #decide}
     * resolves it; each one is kept only if {@link #decide} permits {@code policy:read} on it, so the view
     * never shows a row the per-application read would refuse. It adds no concept: an application outside
     * {@code apps} is not a candidate here.
     */
    public ControlPlaneScope readableApps(String callerSubject, JsonWebToken token) {
        if (isInstallationIdentity(callerSubject)) {
            return ControlPlaneScope.of(new TreeSet<>());
        }
        InstallationMarker marker = installationStore.marker();
        if (!marker.installed()) {
            return isBootstrap(token) ? ControlPlaneScope.unrestrictedScope() : ControlPlaneScope.of(new TreeSet<>());
        }
        if (!boundToThisDeployment(marker)) {
            return ControlPlaneScope.of(new TreeSet<>());
        }
        Map<String, Object> attributes = subjectAttributes(token);
        TreeSet<String> readable = new TreeSet<>();
        if (attributes.get(APPS) instanceof Collection<?> apps) {
            for (Object candidate : apps) {
                if (candidate instanceof String app
                        && permits(app, ControlPlaneAction.READ, callerSubject, attributes)) {
                    readable.add(app);
                }
            }
        }
        return ControlPlaneScope.of(readable);
    }

    /**
     * Records the installation marker when {@code decision} was the bootstrap subject's and the write it
     * authorized has succeeded (ADR-033 §6). Any other decision records nothing, so only the first successful
     * control-plane write accepted from the bootstrap subject closes installation mode.
     *
     * <p>The marker records the reserved application this deployment is running with: from then on a
     * deployment configured with a different one does not start, and one already running is denied
     * everything by {@link #decide}.
     */
    public void writeSucceeded(ControlPlaneDecision decision, String callerSubject) {
        if (decision == ControlPlaneDecision.PERMITTED_AS_BOOTSTRAP) {
            installationStore.recordInstalled(callerSubject, cfg.controlPlane().reservedApp());
        }
    }

    /** @return whether {@code app} is the reserved control-plane application (ADR-033 §6). */
    public boolean isReservedApp(String app) {
        return cfg.controlPlane().reservedApp().equals(app);
    }

    /**
     * The self-lockout guard of ADR-033 §6: resolves a proposed control-plane claim mapping against the
     * caller's own validated token, and answers whether the reserved application is among the resulting
     * {@code apps}. It does not stop a change from excluding other callers; it guarantees that the identity
     * making a change can always undo it.
     *
     * @param proposedMapping the mapping a write would store; {@code null} resolves to nothing.
     */
    public boolean keepsCallerInReservedApp(Map<String, String> proposedMapping, JsonWebToken token) {
        return deriver.resolve(proposedMapping, token).get(APPS) instanceof Collection<?> apps
                && apps.contains(cfg.controlPlane().reservedApp());
    }

    /**
     * @return whether {@code marker} records the identifier this deployment is configured with. A
     *     disagreement is logged when it begins — naming the property and both identifiers, which are not
     *     secret — and not again while it lasts, so it is reported once rather than on every request.
     *     Agreement clears what was reported, so a disagreement that returns is logged again, even with the
     *     same recorded value.
     */
    private boolean boundToThisDeployment(InstallationMarker marker) {
        String configured = cfg.controlPlane().reservedApp();
        if (marker.records(configured)) {
            if (reportedDisagreement.get() != null) {
                reportedDisagreement.set(null);
            }
            return true;
        }
        String recorded =
                marker.reservedApp() == null ? "no identifier this build can read" : "\"" + marker.reservedApp() + "\"";
        if (!recorded.equals(reportedDisagreement.getAndSet(recorded))) {
            log.errorf(
                    "%s is \"%s\", but the installation marker records %s: every control-plane call is denied"
                            + " until this deployment is restarted with the recorded identifier (ADR-033 §6)",
                    RESERVED_APP_PROPERTY, configured, recorded);
        }
        return false;
    }

    /** @return whether {@code callerSubject} is the identity installation writes under, which no caller may use. */
    private static boolean isInstallationIdentity(String callerSubject) {
        return AuditActor.INSTALLATION.equals(callerSubject);
    }

    /** The token, through the mapping stored for the reserved application — never the route's (§3). */
    private Map<String, Object> subjectAttributes(JsonWebToken token) {
        return deriver.derive(cfg.controlPlane().reservedApp(), token);
    }

    private boolean permits(
            String app, ControlPlaneAction action, String callerSubject, Map<String, Object> tokenAttributes) {
        String reservedApp = cfg.controlPlane().reservedApp();
        AuthorizationRequest request = new AuthorizationRequest(
                reservedApp,
                new Subject(callerSubject, tokenAttributes),
                action.action(),
                new Resource(ControlPlaneAction.RESOURCE_TYPE, null, Map.of(APP, app)),
                Map.of());
        List<Policy> candidates = lifecycleStore.activePoliciesFor(reservedApp, ControlPlaneAction.RESOURCE_TYPE);
        return engine.evaluate(selector.select(candidates, request), request).allowed();
    }

    /** @return whether the token carries the configured bootstrap claim value; never without a configured value. */
    private boolean isBootstrap(JsonWebToken token) {
        ServicePolicyConfig.Bootstrap bootstrap = cfg.controlPlane().bootstrap();
        String expected = bootstrap.value().filter(value -> !value.isBlank()).orElse(null);
        if (expected == null || token == null) {
            return false;
        }
        Object found =
                deriver.resolve(Map.of(BOOTSTRAP, bootstrap.claim()), token).get(BOOTSTRAP);
        return expected.equals(found) || (found instanceof Collection<?> values && values.contains(expected));
    }
}
