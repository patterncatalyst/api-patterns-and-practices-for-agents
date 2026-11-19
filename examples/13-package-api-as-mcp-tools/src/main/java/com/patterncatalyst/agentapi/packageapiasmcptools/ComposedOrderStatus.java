package com.patterncatalyst.agentapi.packageapiasmcptools;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.PaymentStatus;
import com.patterncatalyst.agentapi.ShipmentStatus;
import java.math.BigDecimal;

/**
 * The single composed result the {@code orderStatus} tool returns: one order's own state, its
 * payment, and its shipment, reshaped into one response instead of three. A caller that needs
 * only the payment or only the shipment still has {@link OrderMcpTools#paymentStatus} and
 * {@link OrderMcpTools#shipmentTracking}; this record answers "where does this order stand",
 * which none of the three raw tables answers alone.
 */
public record ComposedOrderStatus(String orderId, String customerId, String itemSku, int quantity, BigDecimal amount,
        OrderStatus orderStatus, PaymentStatus paymentStatus, BigDecimal paymentAmount, Carrier carrier,
        String trackingNumber, ShipmentStatus shipmentStatus) {
}
