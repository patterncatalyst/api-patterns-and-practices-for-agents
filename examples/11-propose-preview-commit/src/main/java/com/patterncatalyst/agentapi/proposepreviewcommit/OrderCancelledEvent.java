package com.patterncatalyst.agentapi.proposepreviewcommit;

/**
 * The OrderCancelled event payload (`_plans/domain-model.md`'s event catalog, topic
 * {@code apa.orders.cancelled}). Event payload records live per example, not in domain-model.
 * Only the commit path ever constructs one: a dry run or a REJECTED_BY_USER decision never
 * reaches this type.
 */
public record OrderCancelledEvent(String orderId, String reason, String occurredAt) {
}
