package com.patterncatalyst.agentapi.humanintheloopgate;

/**
 * Thrown when the trigger names an order that exists but cannot legally be cancelled (already
 * {@code CANCELLED} or {@code DELIVERED}); mapped to 409 by the resource. No gate is ever opened
 * for a rejected trigger.
 */
public class OrderNotCancellableException extends RuntimeException {

    public OrderNotCancellableException(String message) {
        super(message);
    }
}
