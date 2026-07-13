package io.github.ricardoqmd.servicepolicy.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Parsing of the external {@code ?status=} vocabulary (ADR-025). */
class HeadStatusTest {

    @Test
    void parsesTheThreeStatusesCaseInsensitively() {
        assertEquals(Optional.of(HeadStatus.ACTIVE), HeadStatus.parse("active"));
        assertEquals(Optional.of(HeadStatus.INACTIVE), HeadStatus.parse("InAcTiVe"));
        assertEquals(Optional.of(HeadStatus.ALL), HeadStatus.parse(" ALL "));
    }

    @Test
    void rejectsUnknownAndAbsentValues() {
        assertTrue(HeadStatus.parse("draft").isEmpty());
        assertTrue(HeadStatus.parse("").isEmpty());
        assertTrue(HeadStatus.parse(null).isEmpty());
    }
}
