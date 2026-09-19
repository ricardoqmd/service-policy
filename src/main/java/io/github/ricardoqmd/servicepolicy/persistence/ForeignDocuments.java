package io.github.ricardoqmd.servicepolicy.persistence;

import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What an application holds that installation did not write (ADR-033 §6): each document named by its kind and
 * identifier, and nothing about what it says.
 *
 * @param configuration    whether the application's configuration document is one of them.
 * @param catalogueEntries the resource types of the catalogue entries among them.
 * @param policies         the ids of the policies with a head or a version among them. A policy is named
 *     once, however many of its documents are foreign.
 */
public record ForeignDocuments(boolean configuration, SortedSet<String> catalogueEntries, SortedSet<String> policies) {

    public ForeignDocuments {
        catalogueEntries = Collections.unmodifiableSortedSet(new TreeSet<>(catalogueEntries));
        policies = Collections.unmodifiableSortedSet(new TreeSet<>(policies));
    }

    /** @return whether the application holds nothing installation did not write. */
    public boolean none() {
        return !configuration && catalogueEntries.isEmpty() && policies.isEmpty();
    }
}
