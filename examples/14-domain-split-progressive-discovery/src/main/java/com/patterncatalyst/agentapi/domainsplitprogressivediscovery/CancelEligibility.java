package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.patterncatalyst.agentapi.OrderStatus;

/** The {@code order_cancel_eligibility_get} tool's result, derived from {@link OrderStatus#canMoveTo}. */
public record CancelEligibility(String orderId, OrderStatus status, boolean cancellable) {
}
