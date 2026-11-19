package com.patterncatalyst.agentapi.instructiveerror;

import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The event edge for cancellation: writes the status transition through {@link OrderService},
 * which throws {@link OrderNotFoundException} or {@link OrderNotCancellableException} before
 * anything is published if the order cannot be cancelled, then publishes exactly one
 * {@code OrderCancelled} event to {@code apa.orders.cancelled}, keyed by order id.
 */
@ApplicationScoped
public class CancelOrderRoute extends RouteBuilder {

    @Inject
    OrderService orderService;

    @Override
    public void configure() {
        from("direct:cancel-order")
                .routeId("cancel-order")
                .process(exchange -> exchange.getMessage().setBody(
                        orderService.cancelOrder(exchange.getIn().getBody(String.class))))
                .setHeader("apaCancelOrderResult", body())
                .setHeader(KafkaConstants.KEY, simple("${body.response.orderId}"))
                .setBody(simple("${body.event}"))
                .marshal().json()
                .to("kafka:" + Topics.ORDERS_CANCELLED)
                .setBody(simple("${header.apaCancelOrderResult.response}"));
    }
}
