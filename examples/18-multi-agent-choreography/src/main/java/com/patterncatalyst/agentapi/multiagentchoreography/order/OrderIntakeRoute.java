package com.patterncatalyst.agentapi.multiagentchoreography.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.patterncatalyst.agentapi.Order;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The one human-facing door into this choreography: a caller places an order over HTTP, never
 * a Kafka event, since something has to start the chain. From here on nothing calls across the
 * order, shipping and notification agent packages directly; every later hop is a Kafka consumer
 * reacting to a Kafka event (see {@code shipping.ShippingWakeupRoute},
 * {@code notification.NotificationWakeupRoute}). A body that is not valid JSON, or fails the
 * {@link Order} constructor checks, never reaches the agent or Kafka, mirroring
 * 00-stack-check's {@code OrderIntakeRoute}.
 */
@ApplicationScoped
public class OrderIntakeRoute extends RouteBuilder {

    @Inject
    OrderAgentBean orderAgentBean;

    @Override
    public void configure() {
        onException(JsonProcessingException.class, IllegalArgumentException.class)
                .handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(simple("rejected: ${exception.originalMessage}"));

        from("platform-http:/orders?httpMethodRestrict=POST")
                .routeId("order-intake")
                .unmarshal().json(Order.class)
                .process(exchange -> orderAgentBean.place(exchange.getIn().getBody(Order.class)))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(constant(""));
    }
}
