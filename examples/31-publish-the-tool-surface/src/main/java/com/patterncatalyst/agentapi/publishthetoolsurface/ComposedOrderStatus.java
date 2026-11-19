package com.patterncatalyst.agentapi.publishthetoolsurface;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.PaymentStatus;
import com.patterncatalyst.agentapi.ShipmentStatus;
import java.math.BigDecimal;

/**
 * One seeded order's own state, its payment and its shipment, held together as the fixed tool
 * surface's only record. There is nothing to write here: the point of this example is that the
 * same build's version shows up identically wherever the surface is reached, not the reads
 * themselves, which {@code OrderMcpTools.orderStatus} composes into exactly this shape.
 */
public record ComposedOrderStatus(String orderId, String customerId, String itemSku, int quantity, BigDecimal amount,
        OrderStatus orderStatus, PaymentStatus paymentStatus, BigDecimal paymentAmount, Carrier carrier,
        String trackingNumber, ShipmentStatus shipmentStatus) {
}
