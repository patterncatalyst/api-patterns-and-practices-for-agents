package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.ShipmentStatus;

/** The {@code shipping_track_get} tool's result: one shipment's own state from {@code shipping.shipments} only. */
public record ShipmentTrackingInfo(String orderId, String shipmentId, Carrier carrier, String trackingNumber,
        ShipmentStatus status) {
}
