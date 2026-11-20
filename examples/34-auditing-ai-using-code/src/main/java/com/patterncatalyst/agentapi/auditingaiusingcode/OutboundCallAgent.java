package com.patterncatalyst.agentapi.auditingaiusingcode;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j {@code AiService} backed directly by {@code quarkus-langchain4j-ollama}
 * (never the Camel {@code langchain4j-agent} component), run unattended by {@link ConsumerBean}
 * with no human in the loop. Its only registered tool is {@link AuditedToolCaller}, the single
 * chokepoint every outbound call this code makes must pass through: whichever order id the model
 * decides to look up, the actual outbound call, and the audit record that precedes it, both happen
 * inside that one tool, never inside this interface.
 *
 * <p>The method returns {@link Result}, not a bare {@code String}, so {@link ConsumerBean} can read
 * {@link Result#toolExecutions()}: the exact text {@link AuditedToolCaller#orderStatus(String)}
 * actually returned, captured by the framework. The durable business record is written from that
 * captured result, never from the model's own prose, so a nondeterministic model cannot change what
 * is recorded as long as it makes the call.
 */
@RegisterAiService(tools = AuditedToolCaller.class)
@ApplicationScoped
public interface OutboundCallAgent {

    @SystemMessage("""
            You are an agent run unattended with no human in the loop. When asked for an order's
            status, call the lookupOrderStatus tool exactly once with the order id, then report
            the status you got back.
            """)
    Result<String> consume(String message);
}
