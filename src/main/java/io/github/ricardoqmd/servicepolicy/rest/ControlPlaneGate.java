package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import jakarta.enterprise.context.RequestScoped;

import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneAction;
import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneAuthorizer;
import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneDecision;
import io.github.ricardoqmd.servicepolicy.controlplane.ControlPlaneScope;
import io.github.ricardoqmd.servicepolicy.persistence.AuditActor;
import io.github.ricardoqmd.servicepolicy.problem.ForbiddenProblemException;

/**
 * The control-plane gate as the web layer applies it (ADR-033): the first thing every control-plane
 * endpoint does.
 *
 * <p>An endpoint hands over exactly two things — the application its route matched and the action — and
 * nothing from the request body. The caller's identity and token come from the validated request context,
 * so what the caller writes in a body cannot reach the decision.
 *
 * <p><strong>A denial says nothing about the application.</strong> Every refusal is the same
 * {@code 403 FORBIDDEN} with the same detail, decided before anything reads the store for the route's
 * application, so the response does not depend on whether that application exists.
 */
@RequestScoped
public class ControlPlaneGate {

    /** The detail of every control-plane denial. It names no application, action or reason. */
    static final String DENIED = "not authorized for this control-plane operation.";

    private final ControlPlaneAuthorizer authorizer;
    private final AuthContext authContext;

    ControlPlaneGate(ControlPlaneAuthorizer authorizer, AuthContext authContext) {
        this.authorizer = authorizer;
        this.authContext = authContext;
    }

    /**
     * @param app the application segment of the request path, as route matching produced it.
     * @return the decision, to hand back to {@link #writeSucceeded} once a write has succeeded.
     * @throws ForbiddenProblemException (403) when the decision is a denial.
     */
    public ControlPlaneDecision authorize(String app, ControlPlaneAction action) {
        ControlPlaneDecision decision = authorizer.decide(
                app,
                action,
                authContext.callerSubject(),
                authContext.callerToken().orElse(null));
        if (!decision.permitted()) {
            throw new ForbiddenProblemException(DENIED);
        }
        return decision;
    }

    /** To be called after a control-plane write succeeds: closes installation mode if that write opened it. */
    public void writeSucceeded(ControlPlaneDecision decision) {
        authorizer.writeSucceeded(decision, authContext.callerSubject());
    }

    /** @return the applications the caller may read, for the merged catalogue (ADR-033 §2). */
    public ControlPlaneScope readableApps() {
        return authorizer.readableApps(
                authContext.callerSubject(), authContext.callerToken().orElse(null));
    }

    /**
     * @param declaredSubject the {@code subject} a write body declares, or {@code null}. It confers no
     *     authority (ADR-033 §4): the write was already authorized as the caller, and this changes only what
     *     the audit records.
     */
    public AuditActor actor(String declaredSubject) {
        return AuditActor.declaring(authContext.callerSubject(), declaredSubject);
    }

    /** @return whether {@code app} is the reserved control-plane application. */
    public boolean isReservedApp(String app) {
        return authorizer.isReservedApp(app);
    }

    /** @return whether a proposed control-plane mapping keeps the caller itself in the reserved application. */
    public boolean keepsCallerInReservedApp(Map<String, String> proposedMapping) {
        return authorizer.keepsCallerInReservedApp(
                proposedMapping, authContext.callerToken().orElse(null));
    }
}
