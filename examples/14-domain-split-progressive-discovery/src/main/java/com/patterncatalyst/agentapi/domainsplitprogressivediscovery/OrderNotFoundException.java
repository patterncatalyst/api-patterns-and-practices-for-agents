package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

/** No row in {@code orders.orders} for the given order id. Raised by the order server's reads only. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("no order with id " + orderId);
    }
}
