package com.patterncatalyst.agentapi.stackcheck;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * A local langchain4j AiService backed directly by quarkus-langchain4j-ollama
 * (not the Camel langchain4j-agent component), so a caller gets a plain Java
 * method and the extension builds the Ollama chat model and the tool wiring.
 */
@RegisterAiService(tools = OrderStatusTool.class)
public interface OrderAssistant {

    @SystemMessage("""
            You are a shipping order assistant. When asked about an order's status,
            call the orderStatus tool with the order id rather than guessing.
            """)
    String chat(String message);
}
