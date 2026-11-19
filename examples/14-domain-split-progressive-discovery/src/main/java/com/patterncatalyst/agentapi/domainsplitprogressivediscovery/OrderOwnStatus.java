package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.patterncatalyst.agentapi.OrderStatus;
import java.math.BigDecimal;

/**
 * The {@code order_status_get} tool's result: one order's own state from {@code orders.orders}
 * only. Unlike ch13's composed {@code orderStatus}, this never reaches into another service's
 * schema; an agent that also needs the shipment calls the shipping server's own
 * {@code shipping_track_get}.
 */
public record OrderOwnStatus(String orderId, String customerId, String itemSku, int quantity, BigDecimal amount,
        OrderStatus status) {
}
