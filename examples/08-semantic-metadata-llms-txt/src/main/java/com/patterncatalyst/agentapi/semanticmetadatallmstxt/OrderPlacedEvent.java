package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

import java.math.BigDecimal;

/** The event published to {@code apa.orders.placed} once a new order is written. */
public record OrderPlacedEvent(
        String orderId, String customerId, String itemSku, int quantity, BigDecimal amount, String occurredAt) {
}
