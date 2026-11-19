package com.patterncatalyst.agentapi.agentbehavioureval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.OrderStatus;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

/**
 * Drives the cancel-and-refund task against the real model once and captures it in the same
 * {@link Transcript} shape {@link TranscriptReader} parses from a committed fixture, so {@link
 * BehaviourEvaluator} scores a live run and a replayed one identically. LIVE mode only (see
 * README); {@code mvn -B verify} never calls this.
 *
 * <p>The generated {@link AgentBehaviourAssistant} proxy is request-scoped, but the Camel route
 * that calls this bean runs on a Vert.x worker thread with no request context active, so this
 * method activates one, the same fix 01-why-agent-apis's {@code AssistantRunner} uses.
 */
@ApplicationScoped
public class LiveRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN_ID = "live-run";

    @Inject
    AgentBehaviourAssistant assistant;

    @Inject
    AgentOrderService service;

    @ActivateRequestContext
    public Transcript run() {
        service.reset();
        Result<String> result = assistant.run(Prompts.CANCEL_AND_REFUND_TASK);

        List<TranscriptToolCall> toolCalls = result.toolExecutions().stream()
                .map(execution -> new TranscriptToolCall(
                        execution.request().name(),
                        parseArgs(execution.request().arguments()),
                        execution.result() != null && execution.result().startsWith("error:")))
                .toList();

        TokenUsage usage = result.tokenUsage();
        int in = usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int out = usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        int total = usage != null && usage.totalTokenCount() != null ? usage.totalTokenCount() : in + out;

        OrderStatus finalStatus = service.status(AgentOrderService.SEED_ORDER_ID);
        boolean refunded = service.isRefunded(AgentOrderService.SEED_ORDER_ID);

        return new Transcript(RUN_ID, AgentOrderService.SEED_ORDER_ID, toolCalls, in, out, total,
                finalStatus.name(), refunded);
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
