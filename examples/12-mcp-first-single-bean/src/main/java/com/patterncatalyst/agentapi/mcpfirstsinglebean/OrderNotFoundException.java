package com.patterncatalyst.agentapi.mcpfirstsinglebean;

/** No order with the given id exists. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("no order with id " + orderId);
    }
}
