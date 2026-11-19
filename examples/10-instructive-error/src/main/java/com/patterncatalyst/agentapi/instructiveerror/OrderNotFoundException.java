package com.patterncatalyst.agentapi.instructiveerror;

/** No order with the given id exists. Maps to 404 via {@link OrderExceptionMappers}. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("no order with id " + orderId);
    }
}
