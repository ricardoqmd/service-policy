package io.github.ricardoqmd.servicepolicy.problem;

/**
 * No action catalogue entry exists for that {@code (app, resourceType)} (ADR-028). An application
 * that has not declared the vocabulary of a resource type has no entry to read, replace or delete —
 * and cannot author policies about it either.
 */
public class CatalogueEntryNotFoundException extends ProblemException {

    public CatalogueEntryNotFoundException(String app, String resourceType) {
        super(
                404,
                "CATALOGUE_ENTRY_NOT_FOUND",
                "No action catalogue entry for resource type '" + resourceType + "' in app '" + app + "'.");
    }
}
