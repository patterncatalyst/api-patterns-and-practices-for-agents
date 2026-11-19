package com.patterncatalyst.agentapi.strictselfdescribingcontract;

/** What {@link OrderService#placeOrder} hands back to the route: the response to return, and
 *  the event to publish. */
public record PlaceOrderResult(OrderResponse response, OrderPlacedEvent event) {
}
