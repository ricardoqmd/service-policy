package io.github.ricardoqmd.servicepolicy.persistence;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;

/** Repository for {@link InstallationDocument} (ADR-033 §6). Reached only through {@link InstallationStore}. */
@ApplicationScoped
public class InstallationRepository implements PanacheMongoRepositoryBase<InstallationDocument, String> {}
