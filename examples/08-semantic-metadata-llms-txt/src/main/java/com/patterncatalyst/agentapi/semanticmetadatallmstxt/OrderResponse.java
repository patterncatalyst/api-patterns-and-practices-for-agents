package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** An order as the agent-facing surface returns it. */
public record OrderResponse(
        @Schema(description = "The order id.") String orderId,
        @Schema(description = "The ordering customer's id.") String customerId,
        @Schema(description = "The ordered item's SKU.") String itemSku,
        @Schema(description = "How many units of the item.") int quantity,
        @Schema(description = "The order total, two decimals.") BigDecimal amount,
        @Schema(description = "The order's lifecycle state.",
                enumeration = {"PLACED", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"}) String status) {
}
