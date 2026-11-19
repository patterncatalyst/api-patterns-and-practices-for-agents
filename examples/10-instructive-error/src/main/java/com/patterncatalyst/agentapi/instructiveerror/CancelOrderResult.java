package com.patterncatalyst.agentapi.instructiveerror;

/** What {@link OrderService#cancelOrder} hands back to the route: the response to return, and
 *  the event to publish. */
public record CancelOrderResult(OrderResponse response, OrderCancelledEvent event) {
}
