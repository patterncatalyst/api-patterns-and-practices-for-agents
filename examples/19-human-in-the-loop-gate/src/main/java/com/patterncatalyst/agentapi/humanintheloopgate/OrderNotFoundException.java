package com.patterncatalyst.agentapi.humanintheloopgate;

/** Thrown when the trigger names an order that does not exist; mapped to 404 by the resource. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
