package io.github.ricardoqmd.servicepolicy.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the fail-fast startup validation in {@link EvaluationConfigValidator} (ADR-031 §2).
 * Exercises the validator directly with stub configs — no Quarkus container needed, mirroring
 * {@link AuthzConfigValidatorTest}.
 */
class EvaluationConfigValidatorTest {

    @Test
    void validatorPassesWithTheDefaultCap() {
        assertDoesNotThrow(() -> validatorFor(100).onStart(null));
    }

    @Test
    void validatorPassesAtTheFloor() {
        assertDoesNotThrow(() -> validatorFor(1).onStart(null));
    }

    /**
     * A cap below 1 rejects every batch at runtime, so the engine refuses to boot instead: the
     * misconfiguration surfaces once, at startup, rather than as a wall of 400s to callers who did
     * nothing wrong.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void validatorThrowsWhenTheCapIsBelowOne(int batchMaxSize) {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> validatorFor(batchMaxSize).onStart(null));
        assertTrue(ex.getMessage().contains("service-policy.evaluation.batch-max-size"));
        assertTrue(ex.getMessage().contains(String.valueOf(batchMaxSize)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static EvaluationConfigValidator validatorFor(int batchMaxSize) {
        return new EvaluationConfigValidator(config(batchMaxSize));
    }

    private static ServicePolicyConfig config(int batchMaxSize) {
        return new ServicePolicyConfig() {
            @Override
            public Info info() {
                return null;
            }

            @Override
            public Authz authz() {
                return null;
            }

            @Override
            public Evaluation evaluation() {
                return () -> batchMaxSize;
            }
        };
    }
}
