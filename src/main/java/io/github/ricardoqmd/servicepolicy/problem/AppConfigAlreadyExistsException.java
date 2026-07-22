package io.github.ricardoqmd.servicepolicy.problem;

/**
 * The application already has a configuration document (ADR-029). Configuration is a singleton per
 * app, so create is not an update: the detail points the caller at {@code PUT}, which is the only
 * way to change an existing document and the one that carries the {@code If-Match} guard.
 */
public class AppConfigAlreadyExistsException extends ProblemException {

    public AppConfigAlreadyExistsException(String app) {
        super(
                409,
                "APP_CONFIG_ALREADY_EXISTS",
                "App '" + app + "' already has a configuration; use PUT to replace it.");
    }
}
