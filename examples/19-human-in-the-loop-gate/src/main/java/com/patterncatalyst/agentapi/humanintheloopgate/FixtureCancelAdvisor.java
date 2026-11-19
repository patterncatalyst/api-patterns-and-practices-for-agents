package com.patterncatalyst.agentapi.humanintheloopgate;

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
import java.util.Locale;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The MODEL=off decision path: replays a committed, recorded reasonCode classification from
 * {@code fixtures/cancel-reason-overrides.jsonl} instead of calling Ollama, so {@code mvn -B
 * verify} (Dev Services) and CI never need a model, mirroring the
 * event-driven-patterns-with-quarkus-and-camel sibling's chapter 24 record/replay discipline (and
 * chapter 17's {@code FixtureAdvisor} in this book). An order id the fixture does not name falls
 * back to the same keyword rule {@link CancelProposalAdvisor}'s system prompt states, so the
 * fallback and the fixture never disagree about what a correctly-behaving agent would have
 * classified. Either path reaches the same {@link CancelProposalTool#proposeCancelAndRefund}, so
 * the gate it opens is identical whichever path produced the classification.
 */
@ApplicationScoped
public class FixtureCancelAdvisor {

    private static final Logger LOG = Logger.getLogger(FixtureCancelAdvisor.class);
    private static final String FIXTURE_PATH = "fixtures/cancel-reason-overrides.jsonl";

    @Inject
    CancelProposalTool tool;

    private final Map<String, String> recordedReasonCodes = load();

    public void propose(String orderId, String reasonText) {
        String reasonCode = recordedReasonCodes.getOrDefault(orderId, ruleReasonCode(reasonText));
        LOG.infof("fixture advisor: orderId=%s reasonCode=%s (recorded=%s)", orderId, reasonCode,
                recordedReasonCodes.containsKey(orderId));
        tool.proposeCancelAndRefund(orderId, reasonCode, reasonText);
    }

    private static String ruleReasonCode(String reasonText) {
        String lower = reasonText == null ? "" : reasonText.toLowerCase(Locale.ROOT);
        if (lower.contains("duplicate")) {
            return "DUPLICATE_ORDER";
        }
        if (lower.contains("fraud")) {
            return "FRAUD_SUSPECTED";
        }
        return "CUSTOMER_REQUEST";
    }

    private static Map<String, String> load() {
        var reasonCodes = new HashMap<String, String>();
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
                    reasonCodes.put(node.get("orderId").asText(), node.get("reasonCode").asText());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + FIXTURE_PATH, e);
        }
        return reasonCodes;
    }
}
