package com.patterncatalyst.agentapi.longrunningagentworkflow;

/** Thrown by {@link FulfillmentService#reserveStock(String, com.patterncatalyst.agentapi.Order)}
 * when the demo-local stock table does not hold enough of the requested SKU. The saga compensates
 * the same way a declined payment does; nothing was reserved, so {@code releaseStock}'s guard
 * (only restoring stock from a row it finds in state {@code STOCK_RESERVED}) is a safe no-op. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String orderId, String itemSku) {
        super("insufficient stock for " + orderId + ": " + itemSku);
    }
}
