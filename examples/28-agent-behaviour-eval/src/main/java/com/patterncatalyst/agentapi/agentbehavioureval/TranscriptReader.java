package com.patterncatalyst.agentapi.agentbehavioureval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the committed JSON-lines transcript format this chapter is about: one JSON object per
 * line, a {@code type} field picking its shape. {@code run} (once, first) names the recorded run
 * and the order it concerns; any number of {@code tool_call} lines capture one tool invocation
 * each, in order; {@code usage} (once) carries the token counts; {@code outcome} (once, last)
 * states the durable effect, the order's final status and whether the refund completed. See
 * README for the full schema; the two committed fixtures under {@code
 * src/main/resources/fixtures/} are worked examples.
 */
public final class TranscriptReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TranscriptReader() {
    }

    public static Transcript read(String jsonLines) {
        String runId = null;
        String orderId = null;
        List<TranscriptToolCall> toolCalls = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        int totalTokens = 0;
        String finalOrderStatus = null;
        boolean refundCompleted = false;
        boolean sawOutcome = false;

        for (String line : jsonLines.lines().map(String::strip).filter(s -> !s.isEmpty()).toList()) {
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("malformed transcript line: " + line, e);
            }
            String type = node.path("type").asText(null);
            if (type == null) {
                throw new IllegalArgumentException("transcript line missing \"type\": " + line);
            }
            switch (type) {
                case "run" -> {
                    runId = node.path("runId").asText(null);
                    orderId = node.path("orderId").asText(null);
                }
                case "tool_call" -> {
                    String name = node.path("name").asText(null);
                    if (name == null) {
                        throw new IllegalArgumentException("tool_call line missing \"name\": " + line);
                    }
                    Map<String, Object> args = MAPPER.convertValue(node.path("args"),
                            new TypeReference<Map<String, Object>>() {
                            });
                    toolCalls.add(new TranscriptToolCall(name, args == null ? Map.of() : args,
                            node.path("argError").asBoolean(false)));
                }
                case "usage" -> {
                    inputTokens = node.path("inputTokens").asInt(0);
                    outputTokens = node.path("outputTokens").asInt(0);
                    totalTokens = node.path("totalTokens").asInt(inputTokens + outputTokens);
                }
                case "outcome" -> {
                    finalOrderStatus = node.path("finalOrderStatus").asText(null);
                    refundCompleted = node.path("refundCompleted").asBoolean(false);
                    sawOutcome = true;
                }
                default -> throw new IllegalArgumentException("unknown transcript line type: " + type);
            }
        }
        if (runId == null || orderId == null) {
            throw new IllegalArgumentException("transcript is missing its \"run\" header line");
        }
        if (!sawOutcome) {
            throw new IllegalArgumentException("transcript is missing its \"outcome\" line");
        }
        return new Transcript(runId, orderId, List.copyOf(toolCalls), inputTokens, outputTokens, totalTokens,
                finalOrderStatus, refundCompleted);
    }
}
