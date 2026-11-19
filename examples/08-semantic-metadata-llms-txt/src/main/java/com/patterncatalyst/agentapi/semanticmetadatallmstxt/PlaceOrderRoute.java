package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The event edge: {@link OrderResource} invokes this {@code direct:} route rather than
 * terminating the HTTP request itself. A new order is written by {@link OrderService}, then
 * exactly one {@code OrderPlaced} event is published to {@code apa.orders.placed}, keyed by
 * order id.
 */
@ApplicationScoped
public class PlaceOrderRoute extends RouteBuilder {

    @Inject
    OrderService orderService;

    @Override
    public void configure() {
        from("direct:place-order")
                .routeId("place-order")
                .process(exchange -> exchange.getMessage().setBody(
                        orderService.placeOrder(exchange.getIn().getBody(OrderCreateRequest.class))))
                .setHeader("apaPlaceOrderResult", body())
                .setHeader(KafkaConstants.KEY, simple("${body.response.orderId}"))
                .setBody(simple("${body.event}"))
                .marshal().json()
                .to("kafka:" + Topics.ORDERS_PLACED)
                .setBody(simple("${header.apaPlaceOrderResult.response}"));
    }
}
