package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The MODEL=off decision path: answers from {@link OrderStatusTool} directly, the same tool the
 * real {@link OrderStatusAdvisor} would call, then feeds {@link TokenCostRecorder} a committed,
 * recorded token count from {@code fixtures/token-usage.jsonl} instead of a real chat model's
 * usage, so {@code mvn -B verify} and CI never need a model. An order id the fixture does not
 * name falls back to a fixed, documented token count, so every call this path answers always has
 * something for the recorder to persist.
 */
@ApplicationScoped
public class FixtureTokenUsageAdvisor {

    private static final Logger LOG = Logger.getLogger(FixtureTokenUsageAdvisor.class);
    private static final String FIXTURE_PATH = "fixtures/token-usage.jsonl";
    private static final String FALLBACK_MODEL = "qwen3-fixture";
    private static final int FALLBACK_INPUT_TOKENS = 64;
    private static final int FALLBACK_OUTPUT_TOKENS = 16;

    /** One fixture's recorded token usage for an order id. */
    private record Usage(String model, int inputTokens, int outputTokens) {
    }

    @Inject
    OrderStatusTool tool;

    @Inject
    TokenCostRecorder recorder;

    private final Map<String, Usage> recordedUsage = load();

    public String answer(String orderId) {
        String answer = tool.orderStatus(orderId);
        Usage usage = recordedUsage.getOrDefault(orderId,
                new Usage(FALLBACK_MODEL, FALLBACK_INPUT_TOKENS, FALLBACK_OUTPUT_TOKENS));
        String callId = UUID.randomUUID().toString();
        LOG.infof("fixture token usage: orderId=%s model=%s inputTokens=%d outputTokens=%d (recorded=%s)",
                orderId, usage.model(), usage.inputTokens(), usage.outputTokens(),
                recordedUsage.containsKey(orderId));
        recorder.recordCost(callId, usage.model(), usage.inputTokens(), usage.outputTokens());
        return answer;
    }

    private static Map<String, Usage> load() {
        var usage = new HashMap<String, Usage>();
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(FIXTURE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on the classpath: " + FIXTURE_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(line);
                    usage.put(node.get("orderId").asText(),
                            new Usage(node.get("model").asText(), node.get("inputTokens").asInt(),
                                    node.get("outputTokens").asInt()));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + FIXTURE_PATH, e);
        }
        return usage;
    }
}
