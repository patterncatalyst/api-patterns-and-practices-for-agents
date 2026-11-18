package com.patterncatalyst.agentapi.stackcheck;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.patterncatalyst.agentapi.Order;
import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * Accepts an Order over HTTP, validates it, records it in the orders schema
 * and publishes it to Kafka keyed by its id.
 *
 * <p>A CDI-managed {@link RouteBuilder} (not a plain one), so {@link OrderRecordStore}
 * is a real injected bean rather than one Camel's bean-call binding would otherwise
 * have to resolve from its registry.
 */
@ApplicationScoped
public class OrderIntakeRoute extends RouteBuilder {

    @Inject
    OrderRecordStore recordStore;

    @Override
    public void configure() {
        // A body that is not valid JSON, or fails the Order constructor checks, never reaches
        // Kafka or Postgres.
        onException(JsonProcessingException.class, IllegalArgumentException.class)
                .handled(true)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"))
                .setBody(simple("rejected: ${exception.originalMessage}"));

        from("platform-http:/orders?httpMethodRestrict=POST")
                .routeId("order-intake")
                .unmarshal().json(Order.class)
                .process(exchange -> {
                    Order order = exchange.getIn().getBody(Order.class);
                    recordStore.recordAndVerify(order.id());
                })
                .setHeader(KafkaConstants.KEY, simple("${body.id}"))
                .marshal().json()
                .to("kafka:" + Topics.ORDERS_PLACED)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .setBody(constant(""));
    }
}
