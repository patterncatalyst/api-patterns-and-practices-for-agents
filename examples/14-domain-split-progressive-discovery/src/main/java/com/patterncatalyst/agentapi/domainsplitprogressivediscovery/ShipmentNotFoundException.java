package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

/**
 * No row in {@code shipping.shipments} for the given order id. Raised by the shipping server's
 * reads only; the shipping server never looks at {@code orders.orders} to tell an unknown order
 * apart from an order that has simply not shipped yet, since it owns only its own schema.
 */
public class ShipmentNotFoundException extends RuntimeException {

    public ShipmentNotFoundException(String orderId) {
        super("no shipment for order id " + orderId);
    }
}
