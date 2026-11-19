package com.patterncatalyst.agentapi.longrunningagentworkflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The MODEL=off decision path: replays a committed, recorded decision from
 * {@code fixtures/carrier-decisions.jsonl} instead of calling Ollama, so {@code mvn -B verify} (Dev
 * Services) and CI never need a model, mirroring 17-event-triggered-agent-wakeup's
 * {@code FixtureAdvisor}. An order id the fixture does not name falls back to the same rule
 * {@link CarrierAdvisor}'s system prompt states (AIRCO at 100.00 or more, otherwise FASTLINE), so
 * the fallback and the fixture never disagree about what a correctly-behaving agent would have
 * decided. Either way the decision reaches the same
 * {@link CarrierBookingTool#bookCarrier(String, String)}.
 */
@ApplicationScoped
public class FixtureCarrierAdvisor {

    private static final Logger LOG = Logger.getLogger(FixtureCarrierAdvisor.class);
    private static final String FIXTURE_PATH = "fixtures/carrier-decisions.jsonl";
    private static final BigDecimal AIRCO_THRESHOLD = new BigDecimal("100.00");

    @Inject
    CarrierBookingTool tool;

    private final Map<String, String> recordedCarriers = load();

    public void advise(String orderId, BigDecimal amount) {
        String carrier = recordedCarriers.getOrDefault(orderId, ruleCarrier(amount));
        LOG.infof("fixture carrier advisor: orderId=%s carrier=%s (recorded=%s)", orderId, carrier,
                recordedCarriers.containsKey(orderId));
        tool.bookCarrier(orderId, carrier);
    }

    private static String ruleCarrier(BigDecimal amount) {
        return amount.compareTo(AIRCO_THRESHOLD) >= 0 ? "AIRCO" : "FASTLINE";
    }

    private static Map<String, String> load() {
        var carriers = new HashMap<String, String>();
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
                    carriers.put(node.get("orderId").asText(), node.get("carrier").asText());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + FIXTURE_PATH, e);
        }
        return carriers;
    }
}
