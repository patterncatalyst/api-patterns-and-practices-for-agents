package com.patterncatalyst.agentapi.multiagentchoreography.events;

import com.patterncatalyst.agentapi.Carrier;
import java.util.Objects;

/**
 * The {@code apa.shipping.scheduled} payload this example publishes: a per-example event
 * record, not a domain-model entity (CLAUDE.md: "Event payload records live per example, not
 * in domain-model"). It carries {@code customerId} forward from the {@code OrderPlaced} event
 * so the notification agent never needs to call back into the order agent's schema to learn
 * who to notify; the two agents still coordinate only by publishing and consuming this event,
 * never by a direct method call.
 */
public record ShipmentScheduledEvent(String orderId, String customerId, Carrier carrier, String trackingNumber) {

    public ShipmentScheduledEvent {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(trackingNumber, "trackingNumber");
    }
}
