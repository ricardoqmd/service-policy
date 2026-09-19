package io.github.ricardoqmd.servicepolicy.problem;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(
        String type,
        String code,
        String title,
        int status,
        String detail,
        String policyId,
        Long currentRevision,
        Integer requestedVersion,
        List<InvalidParam> invalidParams,
        List<String> policyIds,
        Integer maxBatchSize,
        String actionPrefix,
        String resourceType,
        Integer index) {

    /** Every problem except {@code ACTION_RESOURCE_TYPE_MISMATCH}, which alone carries the last three members. */
    public ProblemDetail(
            String type,
            String code,
            String title,
            int status,
            String detail,
            String policyId,
            Long currentRevision,
            Integer requestedVersion,
            List<InvalidParam> invalidParams,
            List<String> policyIds,
            Integer maxBatchSize) {
        this(
                type,
                code,
                title,
                status,
                detail,
                policyId,
                currentRevision,
                requestedVersion,
                invalidParams,
                policyIds,
                maxBatchSize,
                null,
                null,
                null);
    }

    public record InvalidParam(String field, String reason) {}
}
