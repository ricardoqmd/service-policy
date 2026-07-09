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
        List<InvalidParam> invalidParams) {

    public record InvalidParam(String field, String reason) {}
}
