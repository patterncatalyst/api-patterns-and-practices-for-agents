package com.patterncatalyst.agentapi.tokenefficientresponsebff;

import java.math.BigDecimal;

/**
 * The agent BFF projection: the human-readable order id (never the internal {@code recordUuid}
 * or the customer's raw {@code customerUuid}) plus exactly the fields an agent task over an
 * order needs. No {@code internalNotes}, no {@code auditTrail}, no nested customer record: this
 * is a purpose-built projection, not the full entity wrapped in a thinner name. Served by both
 * {@code GET /orders/{id}/summary} and the {@code orderSummary} GraphQL query, over the same
 * {@link OrderReadService}.
 */
public record OrderSummary(String orderId, String customerId, String itemSku, int quantity, BigDecimal amount, String status) {
}
