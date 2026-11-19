package com.patterncatalyst.agentapi.instructiveerror;

import java.math.BigDecimal;

/** An order as this service's agent-facing surface returns it. */
public record OrderResponse(
        String orderId, String customerId, String itemSku, int quantity, BigDecimal amount, String status) {
}
