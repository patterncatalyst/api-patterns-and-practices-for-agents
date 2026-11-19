package com.patterncatalyst.agentapi.multiagentchoreography.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.Order;
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
 * The MODEL=off decision path: replays a committed, recorded decision from {@code
 * fixtures/shipment-decisions.jsonl} instead of calling Ollama, so {@code mvn -B verify} (Dev
 * Services) and CI never need a model, mirroring 17-event-triggered-agent-wakeup's {@code
 * FixtureAdvisor}. An order id the fixture does not name falls back to the same rule
 * {@link ShippingAdvisor}'s system prompt states (AIRCO at 100.00 or more, otherwise FASTLINE),
 * so the fallback and the fixture never disagree about what a correctly-behaving agent would
 * have decided. Either way the decision reaches the same
 * {@link ScheduleShipmentTool#scheduleShipment(String, String, String)}, so the durable record,
 * the counter, the log line and the published event this book's checks assert on are identical
 * whichever path produced the decision.
 */
@ApplicationScoped
public class ShippingFixtureAdvisor {

    private static final Logger LOG = Logger.getLogger(ShippingFixtureAdvisor.class);
    private static final String FIXTURE_PATH = "fixtures/shipment-decisions.jsonl";
    private static final BigDecimal AIRCO_THRESHOLD = new BigDecimal("100.00");

    @Inject
    ScheduleShipmentTool tool;

    private final Map<String, String> recordedCarriers = load();

    public void advise(Order order) {
        String carrier = recordedCarriers.getOrDefault(order.id(), ruleCarrier(order));
        LOG.infof("fixture advisor: orderId=%s carrier=%s (recorded=%s)", order.id(), carrier,
                recordedCarriers.containsKey(order.id()));
        tool.scheduleShipment(order.id(), order.customerId(), carrier);
    }

    private static String ruleCarrier(Order order) {
        return order.amount().compareTo(AIRCO_THRESHOLD) >= 0 ? "AIRCO" : "FASTLINE";
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
