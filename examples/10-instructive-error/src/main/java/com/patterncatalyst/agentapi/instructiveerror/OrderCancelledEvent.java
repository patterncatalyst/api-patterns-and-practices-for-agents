package com.patterncatalyst.agentapi.instructiveerror;

/** The event published to {@code apa.orders.cancelled} once an order's cancellation succeeds. */
public record OrderCancelledEvent(String orderId, String occurredAt) {
}
