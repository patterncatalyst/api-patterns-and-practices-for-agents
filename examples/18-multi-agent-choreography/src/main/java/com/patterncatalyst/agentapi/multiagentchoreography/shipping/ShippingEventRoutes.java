package com.patterncatalyst.agentapi.multiagentchoreography.shipping;

import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The shipping agent's publishing half, and this chapter's one negative witness: the
 * {@code filter()} EIP guarded by {@link ShipmentScheduledDropGuard#allowPublish()} is the
 * single point where the choreography can be cut. With the guard open (the default) every
 * scheduled shipment reaches {@code apa.shipping.scheduled}; with it closed, the message is
 * silently dropped here, never published, so the notification agent's Kafka consumer simply
 * never sees anything for that order. No consumer is touched on either side; only this one link
 * breaks.
 */
@ApplicationScoped
public class ShippingEventRoutes extends RouteBuilder {

    @Inject
    ShipmentScheduledDropGuard dropGuard;

    @Override
    public void configure() {
        from("direct:publishShipmentScheduled")
                .routeId("publish-shipment-scheduled")
                .filter(exchange -> dropGuard.allowPublish())
                .setHeader(KafkaConstants.KEY, simple("${body.orderId}"))
                .marshal().json()
                .to("kafka:" + Topics.SHIPPING_SCHEDULED);
    }
}
