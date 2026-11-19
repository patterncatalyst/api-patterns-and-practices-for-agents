package com.patterncatalyst.agentapi.instructiveerror;

import com.patterncatalyst.agentapi.OrderStatus;
import java.util.Arrays;
import java.util.List;

/**
 * The order exists but its current state has no legal transition to CANCELLED (per
 * {@link OrderStatus#canMoveTo}). Carries the current state and the full set of states the
 * order can legally move to instead, so {@link OrderExceptionMappers} can put both in the 409's
 * remedy, not just say "conflict".
 */
public class OrderNotCancellableException extends RuntimeException {

    private final OrderStatus currentState;
    private final List<OrderStatus> allowedTransitions;

    public OrderNotCancellableException(String orderId, OrderStatus currentState) {
        super("order " + orderId + " cannot be cancelled from status " + currentState);
        this.currentState = currentState;
        this.allowedTransitions = Arrays.stream(OrderStatus.values()).filter(currentState::canMoveTo).toList();
    }

    public OrderStatus currentState() {
        return currentState;
    }

    public List<OrderStatus> allowedTransitions() {
        return allowedTransitions;
    }
}
