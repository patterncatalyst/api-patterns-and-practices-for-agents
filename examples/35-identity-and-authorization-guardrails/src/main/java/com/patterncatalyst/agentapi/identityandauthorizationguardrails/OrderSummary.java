package com.patterncatalyst.agentapi.identityandauthorizationguardrails;

import com.patterncatalyst.agentapi.OrderStatus;
import java.math.BigDecimal;

/** The {@code orderSummary} read response: the order's own fields, nothing about any other
 * service. */
public record OrderSummary(String orderId, String customerId, String itemSku, int quantity, BigDecimal amount,
        OrderStatus status) {
}
