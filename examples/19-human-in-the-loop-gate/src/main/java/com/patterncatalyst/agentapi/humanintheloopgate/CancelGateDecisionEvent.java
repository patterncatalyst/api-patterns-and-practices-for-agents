package com.patterncatalyst.agentapi.humanintheloopgate;

/**
 * The human (or automated) decision that resumes exactly one held proposal, matched by {@code
 * correlationId}. Consumed from {@code apa.orders.decisions} by {@link CancelDecisionRoute}.
 * {@code decision} is {@code APPROVE} or {@code REJECT}; anything else is logged and ignored.
 */
public record CancelGateDecisionEvent(String correlationId, String decision, String decidedBy) {
}
