package com.patterncatalyst.agentapi.searchoverpagination;

import com.patterncatalyst.agentapi.OrderStatus;
import java.math.BigDecimal;

/** One search or page hit. Per-example: the stored status is not part of the shared `Order` record. */
public record OrderSummary(
        String id, String customerId, String itemSku, int quantity, BigDecimal amount, OrderStatus status) {
}
