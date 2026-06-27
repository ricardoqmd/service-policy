package io.github.ricardoqmd.servicepolicy.domain.policy;

/**
 * A single policy rule: when {@code condition} holds, the rule contributes {@code effect}.
 *
 * @param id        stable rule identifier (appears in the decision reason).
 * @param effect    effect contributed when the condition holds.
 * @param condition condition that must hold for the rule to apply.
 */
public record Rule(String id, Effect effect, Condition condition) {}
