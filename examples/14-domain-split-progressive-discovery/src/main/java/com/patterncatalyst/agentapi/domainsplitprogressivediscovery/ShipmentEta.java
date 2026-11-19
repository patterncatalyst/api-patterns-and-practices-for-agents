package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.patterncatalyst.agentapi.ShipmentStatus;

/** The {@code shipping_eta_get} tool's result: a plain-language window derived from {@link ShipmentStatus}. */
public record ShipmentEta(String orderId, ShipmentStatus status, String etaDescription) {
}
