package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Authorization decision returned by the Policy Decision Point.
 *
 * @param allowed       {@code true} if the action is permitted.
 * @param reason        Human-readable explanation for the decision.
 * @param decisionId    Unique identifier for this decision instance (UUID). For audit correlation.
 * @param policyVersion Version of the policy set that produced the decision.
 * @param obligations   Actions the PEP must carry out when enforcing this decision. Empty if none.
 */
@Schema(description = "Authorization decision from the PDP.")
public record Decision(
        @Schema(required = true, description = "true if the action is permitted.")
        boolean allowed,

        @Schema(required = true, description = "Human-readable explanation for the decision.")
        String reason,

        @Schema(required = true, description = "Unique identifier for this decision instance (UUID).")
        String decisionId,

        @Schema(required = true, description = "Policy version that produced the decision.")
        String policyVersion,

        @Schema(description = "Obligations the PEP must fulfill when enforcing this decision.")
        List<Obligation> obligations) {}
