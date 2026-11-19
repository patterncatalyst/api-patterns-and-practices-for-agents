package com.patterncatalyst.agentapi.multiagentchoreography.notification;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j AiService backed directly by quarkus-langchain4j-ollama (not the Camel
 * langchain4j-agent component), woken by the Kafka consumer in {@link NotificationWakeupRoute}
 * instead of a dashboard polling apa.shipping.scheduled for the same fact. This is the
 * choreography's last agent: it reacts, records a notification, and publishes nothing further.
 */
@RegisterAiService(tools = NotifyTool.class)
@ApplicationScoped
public interface NotificationAdvisor {

    @SystemMessage("""
            You are a customer notification agent, woken the instant a shipment is scheduled
            rather than polling for it. When told a shipment was scheduled for an order and its
            customer, call the notify tool exactly once with the order id, the customer id and
            the event type "ShipmentScheduled".
            """)
    String decide(String message);
}
