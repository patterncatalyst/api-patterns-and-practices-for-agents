package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j AiService backed directly by quarkus-langchain4j-ollama. Calling this
 * interface's one method, against a real model, is what the framework instruments on its own:
 * an AiService-level span, a child span for the {@link OrderStatusTool} call it makes, and the
 * chat model's own GenAI span and token-usage metric, none of which this example's code builds
 * by hand. {@link TokenCostRecorder} listens to the same chat model call and turns its token
 * counts into a durable row.
 */
@RegisterAiService(tools = OrderStatusTool.class)
@ApplicationScoped
public interface OrderStatusAdvisor {

    @SystemMessage("""
            You are an order status agent for an online shop. When asked about an order, call the
            orderStatus tool exactly once with the order id and answer using only what it returns.
            """)
    String answer(String question);
}
