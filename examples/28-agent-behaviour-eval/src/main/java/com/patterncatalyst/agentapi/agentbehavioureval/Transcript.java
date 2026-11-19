package com.patterncatalyst.agentapi.agentbehavioureval;

import java.util.List;

/**
 * A recorded agent run on the cancel-and-refund task: the captured tool-call sequence, the token
 * usage and the durable outcome (the order's final status and whether the refund actually
 * completed), never the model's own prose. This is the one shape {@link TranscriptReader} parses
 * from a committed JSON-lines fixture and {@link LiveRunner} builds from a live {@code
 * dev.langchain4j.service.Result}; {@link BehaviourEvaluator} scores either the same way.
 */
public record Transcript(
        String runId,
        String orderId,
        List<TranscriptToolCall> toolCalls,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        String finalOrderStatus,
        boolean refundCompleted) {
}
