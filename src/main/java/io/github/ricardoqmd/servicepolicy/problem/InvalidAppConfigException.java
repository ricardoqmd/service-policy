package io.github.ricardoqmd.servicepolicy.problem;

import java.util.List;

/**
 * A configuration document failed write-time validation (ADR-029 §validated on write).
 *
 * <p>Carries every violation at once, each as an {@link ProblemDetail.InvalidParam} with a dotted
 * field path ({@code pip.timeoutMs}, {@code subjectAttributes.rol}), so an operator fixes the whole
 * document in one pass instead of one round-trip per mistake — the same contract
 * {@link PolicyValidationException} gives a policy author.
 *
 * <p>Validation is syntax and bounds only. It deliberately never dials the configured attribute
 * source: a source that is down when configuration is written is not a configuration error, and a
 * write path that makes outbound calls is a write path that can hang.
 */
public class InvalidAppConfigException extends ProblemException {

    private final List<ProblemDetail.InvalidParam> invalidParams;

    public InvalidAppConfigException(List<ProblemDetail.InvalidParam> invalidParams) {
        super(400, "INVALID_APP_CONFIG", "The configuration document failed validation.");
        this.invalidParams = List.copyOf(invalidParams);
    }

    @Override
    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(getCode()),
                getCode(),
                "Invalid application configuration",
                getStatus(),
                getMessage(),
                null,
                null,
                null,
                invalidParams,
                null);
    }
}
