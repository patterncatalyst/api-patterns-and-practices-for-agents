package com.patterncatalyst.agentapi.mcpfirstsinglebean;

import java.math.BigDecimal;

/**
 * The one business result every face serves, unchanged. {@link OrderOperations#orderSummary}
 * builds exactly one of these; REST serializes it as JSON, GraphQL projects it field by field,
 * and the MCP tool serializes the same instance to the same JSON text.
 */
public record OrderSummary(String orderId, String customerId, String itemSku, int quantity, BigDecimal amount,
        String status) {
}
