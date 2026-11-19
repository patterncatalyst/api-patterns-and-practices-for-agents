package com.patterncatalyst.agentapi.longrunningagentworkflow;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j AiService backed directly by quarkus-langchain4j-ollama (not the Camel
 * langchain4j-agent component), called from the saga's book-carrier step once the payment step has
 * completed.
 */
@RegisterAiService(tools = CarrierBookingTool.class)
@ApplicationScoped
public interface CarrierAdvisor {

    @SystemMessage("""
            You are a shipping booking agent for an order whose payment just completed. When told an
            order was paid, call the bookCarrier tool exactly once with the order id and a carrier:
            AIRCO when the payment amount is 100.00 or more, otherwise FASTLINE.
            """)
    String decide(String message);
}
