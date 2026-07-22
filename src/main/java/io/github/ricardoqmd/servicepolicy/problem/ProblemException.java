package io.github.ricardoqmd.servicepolicy.problem;

public class ProblemException extends RuntimeException {

    private static final String ERRORS_BASE = "https://github.com/ricardoqmd/service-policy/blob/main/docs/ERRORS.md#";

    private final int status;
    private final String code;

    public ProblemException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public ProblemDetail toProblemDetail() {
        return new ProblemDetail(
                typeUri(code), code, defaultTitle(code), status, getMessage(), null, null, null, null, null);
    }

    protected static String typeUri(String code) {
        return ERRORS_BASE + code.toLowerCase().replace('_', '-');
    }

    private static String defaultTitle(String code) {
        return switch (code) {
            case "POLICY_ALREADY_EXISTS" -> "Policy already exists";
            case "INVALID_POLICY" -> "Invalid policy document";
            case "PRECONDITION_REQUIRED" -> "Precondition required";
            case "PRECONDITION_FAILED" -> "Precondition failed";
            case "POLICY_NOT_FOUND" -> "Policy not found";
            case "VERSION_NOT_FOUND" -> "Version not found";
            case "FORBIDDEN" -> "Forbidden";
            case "BAD_REQUEST" -> "Bad request";
            case "CATALOGUE_ENTRY_ALREADY_EXISTS" -> "Action catalogue entry already exists";
            case "CATALOGUE_ENTRY_NOT_FOUND" -> "Action catalogue entry not found";
            case "ACTION_IN_USE" -> "Action in use";
            case "APP_CONFIG_ALREADY_EXISTS" -> "Application configuration already exists";
            case "APP_CONFIG_NOT_FOUND" -> "Application configuration not found";
            case "INVALID_APP_CONFIG" -> "Invalid application configuration";
            default -> code;
        };
    }
}
