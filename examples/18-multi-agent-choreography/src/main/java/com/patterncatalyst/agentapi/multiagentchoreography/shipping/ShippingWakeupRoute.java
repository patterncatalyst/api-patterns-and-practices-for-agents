package com.patterncatalyst.agentapi.multiagentchoreography.shipping;

import com.patterncatalyst.agentapi.Order;
import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

/**
 * The shipping agent's trigger: a Kafka consumer on {@code apa.orders.placed}. Nothing in this
 * package is called from {@code order} directly; this route is the only way shipping learns an
 * order exists, exactly the choreography this chapter is about (every agent reacts to an event,
 * no orchestrator bean calls across agents).
 */
@ApplicationScoped
public class ShippingWakeupRoute extends RouteBuilder {

    @Inject
    ShippingAgentBean shippingAgentBean;

    @Override
    public void configure() {
        from("kafka:" + Topics.ORDERS_PLACED + "?groupId={{shipping.consumer.group-id}}")
                .routeId("shipping-wakeup")
                .unmarshal().json(Order.class)
                .process(exchange -> shippingAgentBean.wake(exchange.getIn().getBody(Order.class)));
    }
}
