package com.patterncatalyst.agentapi.batchandcount;

import com.patterncatalyst.agentapi.OrderStatus;
import java.math.BigDecimal;

/** One order row. Per-example: the stored status is not part of the shared `Order` record. */
public record OrderSummary(
        String id, String customerId, String itemSku, int quantity, BigDecimal amount, OrderStatus status) {
}
