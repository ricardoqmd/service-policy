package io.github.ricardoqmd.servicepolicy.persistence;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.ricardoqmd.servicepolicy.problem.InvalidAppConfigException;
import io.github.ricardoqmd.servicepolicy.problem.ProblemDetail;

/**
 * Write-time validation of a configuration document (ADR-029 §validated on write).
 *
 * <p>Collects <em>every</em> violation before throwing, one {@link ProblemDetail.InvalidParam} per
 * offending field with a dotted path, so an operator fixing a document sees the whole list rather
 * than discovering the next mistake on the next request.
 *
 * <p>Syntax and bounds only. The configured attribute source is never contacted: ADR-029 excludes it
 * deliberately, because a source that happens to be down when configuration is written is not a
 * configuration error, and an admin write that makes outbound calls is an admin write that can hang.
 */
final class AppConfigValidator {

    /** The subject-id placeholder a PIP URL must carry; the future adapter substitutes the real id. */
    private static final String SUB_PLACEHOLDER = "{sub}";

    private static final String CONFIGURATION = "configuration";
    private static final String SUBJECT_ATTRIBUTES = "subjectAttributes";
    private static final String PIP_URL = "pip.url";
    private static final String PIP_TIMEOUT_MS = "pip.timeoutMs";
    private static final String PIP_CACHE_TTL_SECONDS = "pip.cacheTtlSeconds";
    private static final String PIP_CREDENTIAL_REF = "pip.credentialRef";

    private static final int MIN_TIMEOUT_MS = 1;
    private static final int MAX_TIMEOUT_MS = 10_000;
    private static final int MIN_CACHE_TTL_SECONDS = 0;
    private static final int MAX_CACHE_TTL_SECONDS = 86_400;

    private AppConfigValidator() {
        // static helper
    }

    /** @throws InvalidAppConfigException (400) listing every violation found in {@code draft}. */
    static void validate(AppConfigDraft draft) {
        List<ProblemDetail.InvalidParam> errors = new ArrayList<>();

        if (draft.subjectAttributes() == null && draft.pip() == null) {
            errors.add(new ProblemDetail.InvalidParam(
                    CONFIGURATION,
                    "declare at least one of 'subjectAttributes' or 'pip'; an empty configuration"
                            + " configures nothing and is indistinguishable from having none"));
        }
        validateSubjectAttributes(draft.subjectAttributes(), errors);
        validatePip(draft.pip(), errors);

        if (!errors.isEmpty()) {
            throw new InvalidAppConfigException(errors);
        }
    }

    private static void validateSubjectAttributes(
            Map<String, String> subjectAttributes, List<ProblemDetail.InvalidParam> errors) {
        if (subjectAttributes == null) {
            return;
        }
        if (subjectAttributes.isEmpty()) {
            errors.add(new ProblemDetail.InvalidParam(
                    SUBJECT_ATTRIBUTES, "must not be empty; omit the section instead of declaring it empty"));
            return;
        }
        for (Map.Entry<String, String> mapping : subjectAttributes.entrySet()) {
            if (isBlank(mapping.getKey())) {
                errors.add(new ProblemDetail.InvalidParam(SUBJECT_ATTRIBUTES, "attribute names must not be blank"));
                continue;
            }
            if (isBlank(mapping.getValue())) {
                errors.add(new ProblemDetail.InvalidParam(
                        SUBJECT_ATTRIBUTES + "." + mapping.getKey(), "claim path must not be blank"));
            }
        }
    }

    /** Every {@code pip} field is required: a partially configured source cannot be called at all. */
    private static void validatePip(AppConfigDraft.PipDraft pip, List<ProblemDetail.InvalidParam> errors) {
        if (pip == null) {
            return;
        }
        validatePipUrl(pip.url(), errors);
        validateBounds(pip.timeoutMs(), PIP_TIMEOUT_MS, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, errors);
        validateBounds(
                pip.cacheTtlSeconds(), PIP_CACHE_TTL_SECONDS, MIN_CACHE_TTL_SECONDS, MAX_CACHE_TTL_SECONDS, errors);
        if (isBlank(pip.credentialRef())) {
            errors.add(new ProblemDetail.InvalidParam(
                    PIP_CREDENTIAL_REF,
                    "must not be blank; it names a credential in the deployment's secret mechanism"));
        }
    }

    /**
     * The URL must be absolute http/https and must carry {@code {sub}}, which is what makes it a
     * per-subject endpoint rather than one URL for everyone.
     *
     * <p>The placeholder is substituted before parsing because braces are not legal URI characters
     * (RFC 3986): parsing the raw string would reject every correctly-written URL. Substituting a
     * plain token validates everything around the placeholder while allowing the placeholder itself.
     */
    private static void validatePipUrl(String url, List<ProblemDetail.InvalidParam> errors) {
        if (isBlank(url)) {
            errors.add(new ProblemDetail.InvalidParam(PIP_URL, "must not be blank"));
            return;
        }
        if (!url.contains(SUB_PLACEHOLDER)) {
            errors.add(new ProblemDetail.InvalidParam(
                    PIP_URL, "must contain the '" + SUB_PLACEHOLDER + "' placeholder for the subject id"));
        }
        try {
            URI parsed = new URI(url.replace(SUB_PLACEHOLDER, "subject"));
            String scheme = parsed.getScheme();
            boolean httpLike = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpLike || parsed.getHost() == null) {
                errors.add(new ProblemDetail.InvalidParam(PIP_URL, "must be an absolute http or https URL"));
            }
        } catch (URISyntaxException e) {
            errors.add(new ProblemDetail.InvalidParam(PIP_URL, "is not a syntactically valid URL"));
        }
    }

    private static void validateBounds(
            Integer value, String field, int min, int max, List<ProblemDetail.InvalidParam> errors) {
        if (value == null) {
            errors.add(new ProblemDetail.InvalidParam(field, "is required when 'pip' is configured"));
            return;
        }
        if (value < min || value > max) {
            errors.add(new ProblemDetail.InvalidParam(field, "must be between " + min + " and " + max));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
