package com.patterncatalyst.agentapi.langchain4jprogrammaticconsumer;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j {@code AiService} backed directly by {@code quarkus-langchain4j-ollama}
 * (never the Camel {@code langchain4j-agent} component), run unattended by {@link ConsumerBean}
 * with no human in the loop. {@code @McpToolBox("apa-orders")} registers ch13's
 * package-api-as-mcp-tools server's tools (configured by {@code quarkus.langchain4j.mcp.apa-orders.*})
 * as remote tools this method may call over Streamable HTTP, alongside the one local tool,
 * {@link OrderStatusRecorder}, that writes the durable record of the consultation.
 */
@RegisterAiService(tools = OrderStatusRecorder.class)
@ApplicationScoped
public interface OrderStatusConsumerAgent {

    @SystemMessage("""
            You are an order status consumer agent, run unattended with no human in the loop. When
            asked for an order's status, call the orderStatus tool exactly once with the order id,
            then call the recordConsultation tool exactly once with the same order id and the
            orderStatus value from the result.
            """)
    @McpToolBox("apa-orders")
    String consume(String message);
}
