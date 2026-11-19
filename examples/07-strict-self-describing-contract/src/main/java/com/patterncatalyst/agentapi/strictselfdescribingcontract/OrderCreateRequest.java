package com.patterncatalyst.agentapi.strictselfdescribingcontract;

import java.math.BigDecimal;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The body of {@code POST /orders}. Every field is annotated {@code required = true}, so the
 * generated OpenAPI document states exactly what a caller must send, not just what the Java
 * record happens to accept.
 */
public record OrderCreateRequest(
        @Schema(description = "The ordering customer's id.", required = true) String customerId,
        @Schema(description = "The ordered item's SKU.", required = true) String itemSku,
        @Schema(description = "How many units of the item.", required = true) int quantity,
        @Schema(description = "The order total, two decimals.", required = true) BigDecimal amount) {
}
