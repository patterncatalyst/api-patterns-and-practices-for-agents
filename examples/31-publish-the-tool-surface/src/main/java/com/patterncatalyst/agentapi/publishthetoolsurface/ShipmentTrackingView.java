package com.patterncatalyst.agentapi.publishthetoolsurface;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.ShipmentStatus;

/** One order's shipment, read on its own: the {@code shipmentTracking} tool's result. */
public record ShipmentTrackingView(String orderId, Carrier carrier, String trackingNumber, ShipmentStatus status) {
}
