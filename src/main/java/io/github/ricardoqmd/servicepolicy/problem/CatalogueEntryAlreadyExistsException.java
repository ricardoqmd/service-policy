package io.github.ricardoqmd.servicepolicy.problem;

/**
 * The application already declares an action catalogue for that resource type (ADR-028). The
 * catalogue is keyed by {@code (app, resourceType)}, so the conflict is scoped to the app in the
 * path: the same resource type in another application is a different, independent entry.
 */
public class CatalogueEntryAlreadyExistsException extends ProblemException {

    public CatalogueEntryAlreadyExistsException(String app, String resourceType) {
        super(
                409,
                "CATALOGUE_ENTRY_ALREADY_EXISTS",
                "An action catalogue entry for resource type '" + resourceType + "' already exists in app '" + app
                        + "'.");
    }
}
