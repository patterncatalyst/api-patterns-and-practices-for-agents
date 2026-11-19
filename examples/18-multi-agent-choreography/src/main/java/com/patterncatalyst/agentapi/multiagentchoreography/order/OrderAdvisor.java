package com.patterncatalyst.agentapi.multiagentchoreography.order;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j AiService backed directly by quarkus-langchain4j-ollama (not the Camel
 * langchain4j-agent component). It is the order agent's half of the choreography: it owns
 * recording the order and publishing {@code apa.orders.placed}, and knows nothing about
 * shipping or notification beyond the fact that publishing the event is its whole job.
 */
@RegisterAiService(tools = OrderPlacementTool.class)
@ApplicationScoped
public interface OrderAdvisor {

    @SystemMessage("""
            You are an order placement agent. When told a new order's id, customerId, itemSku,
            quantity and amount, call the placeOrder tool exactly once with those five values
            exactly as given, then stop.
            """)
    String decide(String message);
}
