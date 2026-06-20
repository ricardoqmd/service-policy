package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Batch of evaluation requests to be processed in a single call.
 *
 * @param requests List of authorization requests. Results are returned in the same order.
 */
@Schema(description = "Batch of authorization evaluation requests.")
public record BatchEvaluationRequest(
        @Schema(required = true, description = "Requests to evaluate. Results match the input order.")
        List<EvaluationRequest> requests) {}
