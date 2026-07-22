package io.github.ricardoqmd.servicepolicy.problem;

/**
 * The application has no configuration document (ADR-029).
 *
 * <p>Only the administrative surface ever sees this. Evaluation does not: an app without
 * configuration evaluates exactly as the engine did before ADR-029 — no claim mapping, no attribute
 * source — because absent configuration removes derived capability rather than denying anything.
 */
public class AppConfigNotFoundException extends ProblemException {

    public AppConfigNotFoundException(String app) {
        super(404, "APP_CONFIG_NOT_FOUND", "No configuration for app '" + app + "'.");
    }
}
