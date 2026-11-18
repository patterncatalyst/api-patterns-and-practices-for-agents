package com.patterncatalyst.agentapi;

/** Shipment lifecycle: one step forward, no skipping and no going back. */
public enum ShipmentStatus {
    PENDING, SCHEDULED, IN_TRANSIT, DELIVERED;

    public boolean canMoveTo(ShipmentStatus next) {
        return switch (this) {
            case PENDING -> next == SCHEDULED;
            case SCHEDULED -> next == IN_TRANSIT;
            case IN_TRANSIT -> next == DELIVERED;
            case DELIVERED -> false;
        };
    }
}
