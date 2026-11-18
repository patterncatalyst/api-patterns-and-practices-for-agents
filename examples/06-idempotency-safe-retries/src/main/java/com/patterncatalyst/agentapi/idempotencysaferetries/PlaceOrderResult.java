package com.patterncatalyst.agentapi.idempotencysaferetries;

/**
 * What the route hands back to {@link OrderResource}: the HTTP status and raw JSON body to
 * send, and the event to publish to Kafka, if any. {@code event} is non-null only the first
 * time a key is processed; a replay or a rejected conflict publishes nothing.
 */
public record PlaceOrderResult(int statusCode, String body, OrderPlacedEvent event) {
}
