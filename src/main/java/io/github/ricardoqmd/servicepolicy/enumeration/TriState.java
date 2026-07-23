package io.github.ricardoqmd.servicepolicy.enumeration;

/**
 * The three truth values of permission enumeration (ADR-030 §4): a condition, and a policy, may be
 * decided or may hang on a resource instance that is not in hand.
 *
 * <p>This type exists precisely so that the value {@code INDETERMINATE} cannot escape the
 * enumeration package. Enforcement is two-valued and stays that way (ADR-011): a {@code Decision} is
 * {@code allowed} or not, and there is no third case for a caller to mishandle. Absence yields
 * INDETERMINATE <em>here</em> and {@code false} (deny) <em>there</em>; keeping the third value in a
 * type that only the enumeration path speaks is what makes that divergence structural rather than a
 * convention someone can forget. An ArchUnit rule forbids {@code ..evaluation..} and
 * {@code ..domain..} from importing this package.
 */
public enum TriState {
    TRUE,
    FALSE,
    INDETERMINATE
}
