package com.patterncatalyst.agentapi.humanintheloopgate;

/**
 * The held proposal published once per {@code proposeCancelAndRefund} tool call, to {@code
 * apa.orders.pending-decisions}, keyed by {@code correlationId}. Publishing this is the entire
 * extent of the agent's action: the cancel-and-refund side effect itself waits for a
 * {@link CancelGateDecisionEvent} carrying the same correlation id.
 */
public record PendingDecisionEvent(String correlationId, String orderId, String proposedAction, String reasonCode,
        String amount, String reason, String proposedAt) {
}
