package com.patterncatalyst.agentapi.tokenefficientresponsebff;

/** One entry in an order's internal audit trail. Part of {@link OrderFull} only. */
public record AuditEntry(String actor, String action, String occurredAt) {
}
