package com.patterncatalyst.agentapi.idempotencysaferetries;

import java.math.BigDecimal;

/** The body of {@code POST /orders}. The domain `Order` record validates these fields when the
 *  saga builds the order; this per-example record carries no order id, since the service
 *  generates one on first execution. */
public record OrderCreateRequest(String customerId, String itemSku, int quantity, BigDecimal amount) {
}
