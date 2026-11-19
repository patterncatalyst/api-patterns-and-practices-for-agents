package com.patterncatalyst.agentapi.tokenefficientresponsebff;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The full human payload: every column this service holds for an order, including the record's
 * own internal surrogate key, the raw {@link CustomerRecord}, free-text internal notes and the
 * complete audit trail. Served by {@code GET /orders/{id}} for a human-facing dashboard; never
 * the shape an agent-facing caller should have to parse.
 */
public record OrderFull(
        UUID recordUuid,
        String orderId,
        CustomerRecord customer,
        String itemSku,
        int quantity,
        BigDecimal amount,
        String status,
        String internalNotes,
        List<AuditEntry> auditTrail) {
}
