package com.patterncatalyst.agentapi.multiagentchoreography.order;

import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The order agent's publishing half: a Camel route, not a hand-rolled Jackson call, marshals
 * whatever is sent to {@code direct:publishOrderPlaced} to JSON and publishes it keyed by its
 * order id, the same {@code setHeader}/{@code marshal().json()}/{@code to("kafka:...")} shape
 * 00-stack-check's {@code OrderIntakeRoute} uses.
 */
@ApplicationScoped
public class OrderEventRoutes extends RouteBuilder {

    @Override
    public void configure() {
        from("direct:publishOrderPlaced")
                .routeId("publish-order-placed")
                .setHeader(KafkaConstants.KEY, simple("${body.id}"))
                .marshal().json()
                .to("kafka:" + Topics.ORDERS_PLACED);
    }
}
