package com.patterncatalyst.agentapi.consumingtheasyncside;

import com.patterncatalyst.agentapi.Carrier;
import java.util.Objects;

/**
 * The event payload this example consumes on {@code apa.shipping.delivered}. Event payload
 * records live per example, never in {@code domain-model}: this one carries exactly what a
 * delivery notification needs, the order id, the customer to notify, the carrier that delivered
 * it and the tracking number, nothing from {@code Shipment}'s own lifecycle state.
 */
public record ShipmentDelivered(String orderId, String customerId, Carrier carrier, String trackingNumber) {

    public ShipmentDelivered {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(trackingNumber, "trackingNumber");
    }
}
