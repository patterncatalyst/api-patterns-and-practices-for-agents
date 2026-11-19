package com.patterncatalyst.agentapi.proposepreviewcommit;

import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The event/EIP edge for the commit path only: a CDI-managed route that runs the saga, then
 * publishes the resulting OrderCancelled event keyed by order id. {@link OrderResource} reaches
 * this route through a {@code direct:} endpoint exactly when {@code dryRun} is false and the
 * decision is not {@code REJECTED_BY_USER}; a preview never calls {@code requestBodyAndHeader}
 * at all, so it can never reach this publish step.
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
