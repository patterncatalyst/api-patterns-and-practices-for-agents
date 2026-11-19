package com.patterncatalyst.agentapi.packageapiasmcptools;

/** No order, payment or shipment row exists for the given order id. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("no order with id " + orderId);
    }
}
