package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Batch evaluation result: one {@link Decision} per input request, in the same order.
 *
 * @param decisions Evaluation decisions matching the corresponding input requests.
 */
@Schema(description = "Batch evaluation result.")
public record BatchEvaluationResult(
        @Schema(required = true, description = "One decision per input request, in the same order.")
        List<Decision> decisions) {}
