package com.patterncatalyst.agentapi.consumingtheasyncside;

import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The consuming side of this book's async API surface: a Kafka consumer on
 * {@code apa.shipping.delivered} wakes the notification agent the instant a shipment is
 * delivered, instead of a dashboard polling the same topic for the same fact.
 * {@code consumer.async.enabled=false} (demo code, the negative witness in verify.sh) stops this
 * route entirely, so an event published while it is down sits on the topic unconsumed until the
 * route is restarted and reads it from where it left off.
 */
@ApplicationScoped
public class ShipmentDeliveredRoute extends RouteBuilder {

    @Inject
    NotificationBean notificationBean;

    @ConfigProperty(name = "consumer.async.enabled", defaultValue = "true")
    boolean consumerEnabled;

    @Override
    public void configure() {
        from("kafka:" + Topics.SHIPPING_DELIVERED + "?groupId={{consumer.async.group-id}}")
                .routeId("shipment-delivered-consumer")
                .autoStartup(consumerEnabled)
                .unmarshal().json(ShipmentDelivered.class)
                .process(exchange -> notificationBean.notify(exchange.getIn().getBody(ShipmentDelivered.class)));
    }
}
