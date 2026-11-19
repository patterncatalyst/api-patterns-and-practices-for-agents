package com.patterncatalyst.agentapi.publishthetoolsurface;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.PaymentStatus;
import com.patterncatalyst.agentapi.ShipmentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.Map;

/**
 * A fixed, in-memory tool surface: one seeded order, held in a plain map instead of a database.
 * What this example proves is that one version string reaches the HTTP handshake, the stdio
 * handshake and the served {@code /llms.txt} identically; a datasource would add a second moving
 * part the handshake and llms.txt checks do not need, and would also keep stdio from starting
 * over pure stdin/stdout with no network at all.
 */
@ApplicationScoped
public class OrderCatalog {

    private static final String SEEDED_ORDER_ID = "O-9001";

    private final Map<String, ComposedOrderStatus> orders = Map.of(SEEDED_ORDER_ID,
            new ComposedOrderStatus(SEEDED_ORDER_ID, "CUST-77", "SKU-4", 3, new BigDecimal("88.50"),
                    OrderStatus.SHIPPED, PaymentStatus.COMPLETED, new BigDecimal("88.50"), Carrier.FASTLINE,
                    "FL-778821", ShipmentStatus.IN_TRANSIT));

    public ComposedOrderStatus orderStatus(String orderId) {
        return require(orderId);
    }

    public PaymentStatusView paymentStatus(String orderId) {
        ComposedOrderStatus order = require(orderId);
        return new PaymentStatusView(order.orderId(), order.paymentStatus(), order.paymentAmount());
    }

    public ShipmentTrackingView shipmentTracking(String orderId) {
        ComposedOrderStatus order = require(orderId);
        return new ShipmentTrackingView(order.orderId(), order.carrier(), order.trackingNumber(),
                order.shipmentStatus());
    }

    private ComposedOrderStatus require(String orderId) {
        ComposedOrderStatus found = orders.get(orderId);
        if (found == null) {
            throw new OrderNotFoundException(orderId);
        }
        return found;
    }
}
