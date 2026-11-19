package com.patterncatalyst.agentapi.multiagentchoreography.shipping;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j AiService backed directly by quarkus-langchain4j-ollama (not the Camel
 * langchain4j-agent component), woken by the Kafka consumer in {@link ShippingWakeupRoute}
 * instead of a dashboard polling apa.orders.placed for the same fact. This is the shipping
 * agent's whole half of the choreography: react to the order, choose a carrier, schedule the
 * shipment, publish the next event. It knows nothing about the order agent that woke it or the
 * notification agent it wakes in turn, beyond the two topics.
 */
@RegisterAiService(tools = ScheduleShipmentTool.class)
@ApplicationScoped
public interface ShippingAdvisor {

    @SystemMessage("""
            You are a shipping scheduling agent, woken the instant an order is placed rather than
            polling for it. When told an order was placed, call the scheduleShipment tool exactly
            once with the order id and a carrier: AIRCO when the order amount is 100.00 or more,
            otherwise FASTLINE.
            """)
    String decide(String message);
}
