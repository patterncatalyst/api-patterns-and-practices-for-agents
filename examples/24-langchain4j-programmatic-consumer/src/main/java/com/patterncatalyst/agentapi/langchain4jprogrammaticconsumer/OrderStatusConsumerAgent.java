package com.patterncatalyst.agentapi.langchain4jprogrammaticconsumer;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mcp.runtime.McpToolBox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j {@code AiService} backed directly by {@code quarkus-langchain4j-ollama}
 * (never the Camel {@code langchain4j-agent} component), run unattended by {@link ConsumerBean}
 * with no human in the loop. {@code @McpToolBox("apa-orders")} registers ch13's
 * package-api-as-mcp-tools server's tools (configured by {@code quarkus.langchain4j.mcp.apa-orders.*})
 * as remote tools this method may call over Streamable HTTP.
 *
 * <p>The method returns {@link Result}, not a bare {@code String}, so {@link ConsumerBean} can read
 * {@link Result#toolExecutions()}: the exact text the remote {@code orderStatus} tool returned,
 * captured by the framework. The durable record is written from that captured result, never from the
 * model's own prose, so a nondeterministic model cannot change what is recorded as long as it makes
 * the call. The model's only job is to call {@code orderStatus} once with the order id.
 */
@RegisterAiService
@ApplicationScoped
public interface OrderStatusConsumerAgent {

    @SystemMessage("""
            You are an order status consumer agent, run unattended with no human in the loop. When
            asked for an order's status, call the orderStatus tool exactly once with the order id,
            then report the status you got back.
            """)
    @McpToolBox("apa-orders")
    Result<String> consume(String message);
}
