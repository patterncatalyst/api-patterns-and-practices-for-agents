package com.patterncatalyst.agentapi.idempotencysaferetries;

import java.math.BigDecimal;

/**
 * The OrderPlaced event payload (`_plans/domain-model.md`'s event catalog, topic
 * {@code apa.orders.placed}). Event payload records live per example, not in domain-model.
 */
public record OrderPlacedEvent(
        String orderId, String customerId, String itemSku, int quantity, BigDecimal amount, String occurredAt) {
}
