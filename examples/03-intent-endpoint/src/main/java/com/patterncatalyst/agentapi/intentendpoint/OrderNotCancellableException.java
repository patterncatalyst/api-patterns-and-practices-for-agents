package com.patterncatalyst.agentapi.intentendpoint;

/**
 * The order exists but is not in a cancellable state (only PLACED and PAID are, per
 * `_plans/domain-model.md`'s lifecycle rule). Maps to 409.
 */
public class OrderNotCancellableException extends RuntimeException {

    public OrderNotCancellableException(String message) {
        super(message);
    }
}
