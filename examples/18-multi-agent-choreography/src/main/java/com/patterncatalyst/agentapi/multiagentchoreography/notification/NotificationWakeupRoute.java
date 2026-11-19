package com.patterncatalyst.agentapi.multiagentchoreography.notification;

import com.patterncatalyst.agentapi.Topics;
import com.patterncatalyst.agentapi.multiagentchoreography.events.ShipmentScheduledEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

/**
 * The notification agent's trigger, and the choreography's terminal hop: a Kafka consumer on
 * {@code apa.shipping.scheduled}. Nothing in this package is called from {@code shipping}
 * directly; this route is the only way notification learns a shipment was scheduled. This agent
 * emits no further event (the domain model's notification-service "reacts to every event, emits
 * none"), so the chain ends at the durable {@code notifications.notifications} row
 * {@link NotifyTool} writes.
 */
@ApplicationScoped
public class NotificationWakeupRoute extends RouteBuilder {

    @Inject
    NotificationAgentBean notificationAgentBean;

    @Override
    public void configure() {
        from("kafka:" + Topics.SHIPPING_SCHEDULED + "?groupId={{notification.consumer.group-id}}")
                .routeId("notification-wakeup")
                .unmarshal().json(ShipmentScheduledEvent.class)
                .process(exchange ->
                        notificationAgentBean.wake(exchange.getIn().getBody(ShipmentScheduledEvent.class)));
    }
}
