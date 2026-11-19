package com.patterncatalyst.agentapi.humanintheloopgate;

/**
 * The gate's current state for one order: the latest proposal, if more than one was ever opened.
 * {@code status} is {@code PENDING}, {@code APPROVED_EXECUTED} or {@code REJECTED_BY_USER}.
 */
public record CancelGateRecord(String correlationId, String orderId, String status, String reason,
        String reasonCode, String amount, String proposedAt, String decidedAt) {
}
