package com.patterncatalyst.agentapi.publishthetoolsurface;

/** No seeded order exists for the given order id. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("no order with id " + orderId);
    }
}
