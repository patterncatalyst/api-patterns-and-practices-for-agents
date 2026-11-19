package com.patterncatalyst.agentapi.consumingtheasyncside;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j {@code AiService} backed directly by {@code quarkus-langchain4j-ollama}
 * (never the Camel {@code langchain4j-agent} component), woken by the Kafka consumer in
 * {@link ShipmentDeliveredRoute} instead of a dashboard polling {@code apa.shipping.delivered} for
 * the same fact. This agent carries no tool: its only job is to compose the customer-facing
 * notification prose. The structured fields the durable record is keyed and asserted on, order id,
 * customer id and event type, come from the consumed {@link ShipmentDelivered} event itself,
 * written by {@link NotificationBean} and {@link NotificationRecorder}, never read back out of
 * this agent's reply.
 */
@RegisterAiService
@ApplicationScoped
public interface NotificationComposerAgent {

    @SystemMessage("""
            You write short, friendly delivery notification messages for customers, run unattended
            with no human in the loop. Given an order id, a carrier and a tracking number, write one
            or two sentences confirming the delivery. State only the facts you were given; do not
            invent an order detail you were not told.
            """)
    String compose(String message);
}
