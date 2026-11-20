package com.patterncatalyst.agentapi.identityandauthorizationguardrails;

import com.patterncatalyst.agentapi.OrderStatus;

/** The order exists but its current state does not allow cancellation: {@link OrderStatus#canMoveTo}
 * only permits CANCELLED from PLACED or PAID. */
public class OrderNotCancellableException extends RuntimeException {

    public OrderNotCancellableException(String orderId, OrderStatus status) {
        super("order " + orderId + " is " + status + " and cannot be cancelled "
                + "(only PLACED or PAID orders can be)");
    }
}
