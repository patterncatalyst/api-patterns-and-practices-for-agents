package com.patterncatalyst.agentapi.strictselfdescribingcontract;

import com.patterncatalyst.agentapi.OrderStatus;
import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An order as the agent-facing surface returns it. {@code status} is serialized as a plain
 * string (Jackson's default for an enum field), so without the explicit {@code enumeration}
 * list below the generated schema would show only {@code type: string}: the five
 * {@link OrderStatus} values would not appear in the contract at all.
 */
public record OrderResponse(
        @Schema(description = "The order id.") String orderId,
        @Schema(description = "The ordering customer's id.") String customerId,
        @Schema(description = "The ordered item's SKU.") String itemSku,
        @Schema(description = "How many units of the item.") int quantity,
        @Schema(description = "The order total, two decimals.") BigDecimal amount,
        @Schema(description = "The order's lifecycle state.",
                enumeration = {"PLACED", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"}) String status) {
}
