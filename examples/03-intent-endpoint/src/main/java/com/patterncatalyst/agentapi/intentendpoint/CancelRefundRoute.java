package com.patterncatalyst.agentapi.intentendpoint;

import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The event/EIP edge: a CDI-managed route that runs the saga, then publishes the resulting
 * OrderCancelled event keyed by order id. The agent-facing HTTP surface is Quarkus REST
 * ({@link OrderResource}), which invokes this route through a {@code direct:} endpoint; Camel
 * never terminates an HTTP request directly in this book from r03 onward.
 */
@ApplicationScoped
public class CancelRefundRoute extends RouteBuilder {

    @Inject
    CancelRefundSaga saga;

    @Override
    public void configure() {
        from("direct:cancel-and-refund")
                .routeId("cancel-and-refund")
                .process(exchange -> {
                    String orderId = exchange.getIn().getHeader("orderId", String.class);
                    String reason = exchange.getIn().getBody(String.class);
                    OrderCancelledEvent event = saga.cancelAndRefund(orderId, reason);
                    exchange.getMessage().setBody(event);
                })
                .setHeader(KafkaConstants.KEY, simple("${body.orderId}"))
                .setHeader("apaCancelledEvent", body())
                .marshal().json()
                .to("kafka:" + Topics.ORDERS_CANCELLED)
                .setBody(header("apaCancelledEvent"));
    }
}
