package com.patterncatalyst.agentapi.intentendpoint;

/**
 * The OrderCancelled event payload (`_plans/domain-model.md`'s event catalog, topic
 * {@code apa.orders.cancelled}). Event payload records live per example, not in domain-model.
 */
public record OrderCancelledEvent(String orderId, String reason, String occurredAt) {
}
