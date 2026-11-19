package com.patterncatalyst.agentapi.eventtriggeredagentwakeup;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j AiService backed directly by quarkus-langchain4j-ollama (not the Camel
 * langchain4j-agent component), woken by the Kafka consumer in {@link PaymentWakeupRoute}
 * instead of a dashboard polling apa.payments.processed for the same fact.
 */
@RegisterAiService(tools = ShipmentPrepTool.class)
@ApplicationScoped
public interface ShipmentAdvisor {

    @SystemMessage("""
            You are a shipping readiness agent, woken the instant a payment is processed rather
            than polling for it. When told a payment was processed for an order, call the
            prepShipment tool exactly once with the order id and a carrier: AIRCO when the
            payment amount is 100.00 or more, otherwise FASTLINE.
            """)
    String decide(String message);
}
