package io.github.ricardoqmd.servicepolicy.evaluation;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An obligation is a requirement placed on the PEP when enforcing a decision.
 *
 * <p>Example: {@code Obligation("audit", {"level": "high"})} instructs the PEP to write
 * a high-priority audit log entry for this decision.
 *
 * @param type       Obligation type identifier (e.g. {@code audit}, {@code notify}).
 * @param attributes Obligation-specific parameters.
 */
@Schema(description = "Obligation the PEP must fulfill when enforcing a permit or deny decision.")
public record Obligation(
        @Schema(required = true, description = "Obligation type identifier.")
        String type,

        @Schema(description = "Obligation-specific parameters.")
        Map<String, Object> attributes) {}
