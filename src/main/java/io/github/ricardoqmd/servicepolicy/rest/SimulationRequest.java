package io.github.ricardoqmd.servicepolicy.rest;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import io.github.ricardoqmd.servicepolicy.evaluation.EvaluationRequest;

/**
 * Body of a policy-simulation call (ADR-027): a candidate policy document plus the case to evaluate
 * it against, neither of which is persisted.
 *
 * <p>{@code policy} is the raw policy document — the same shape as a create body — carried as an
 * untyped {@code Map} so it flows through the identical {@code PolicyDocumentMapper.fromDocument}
 * authoring validator that create/append use (a body-carried {@code app} is rejected there with
 * {@code INVALID_POLICY}, ADR-026). {@code request} is the evaluation case — the same shape as an
 * evaluate body; being a typed {@link EvaluationRequest}, a stray {@code app} field is rejected at
 * deserialization with {@code BAD_REQUEST}. Both are scoped to the path's app (ADR-026); neither
 * carries {@code app} of its own.
 *
 * @param policy  the candidate policy document to simulate (same shape as a create body, no {@code app}).
 * @param request the authorization case to evaluate against it (same shape as an evaluate body, no {@code app}).
 */
@Schema(description = "A candidate policy document and the case to simulate it against (ADR-027).")
public record SimulationRequest(
        @Schema(required = true, description = "Candidate policy document (same shape as a create body, no 'app').")
        Map<String, Object> policy,

        @Schema(
                required = true,
                description = "Authorization case to evaluate (same shape as an evaluate body, no 'app').")
        EvaluationRequest request) {}
