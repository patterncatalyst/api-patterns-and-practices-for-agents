package com.patterncatalyst.agentapi;

import java.util.List;

/** Kafka topic names, so no example hard-codes a typo. */
public final class Topics {

    public static final String ORDERS_PLACED = "apa.orders.placed";
    public static final String ORDERS_CANCELLED = "apa.orders.cancelled";
    public static final String INVENTORY_RESERVED = "apa.inventory.reserved";
    public static final String INVENTORY_INSUFFICIENT = "apa.inventory.insufficient";
    public static final String PAYMENTS_PROCESSED = "apa.payments.processed";
    public static final String PAYMENTS_FAILED = "apa.payments.failed";
    public static final String SHIPPING_SCHEDULED = "apa.shipping.scheduled";
    public static final String SHIPPING_IN_TRANSIT = "apa.shipping.in-transit";
    public static final String SHIPPING_DELIVERED = "apa.shipping.delivered";

    /** The nine event catalog topics. */
    public static final List<String> ALL = List.of(
            ORDERS_PLACED, ORDERS_CANCELLED,
            INVENTORY_RESERVED, INVENTORY_INSUFFICIENT,
            PAYMENTS_PROCESSED, PAYMENTS_FAILED,
            SHIPPING_SCHEDULED, SHIPPING_IN_TRANSIT, SHIPPING_DELIVERED);

    private Topics() {
    }
}
