package com.patterncatalyst.agentapi.eventtriggeredagentwakeup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.Payment;
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
 * {@code fixtures/wakeup-decisions.jsonl} instead of calling Ollama, so {@code mvn -B verify}
 * (Dev Services) and CI never need a model, mirroring the
 * event-driven-patterns-with-quarkus-and-camel sibling's chapter 24 record/replay discipline. An
 * order id the fixture does not name falls back to the same rule {@link ShipmentAdvisor}'s
 * system prompt states (AIRCO at 100.00 or more, otherwise FASTLINE), so the fallback and the
 * fixture never disagree about what a correctly-behaving agent would have decided. Either way
 * the decision reaches the same {@link ShipmentPrepTool#prepShipment(String, String)}, so the
 * durable record, the counter and the log line this book's checks assert on are identical
 * whichever path produced the decision.
 */
@ApplicationScoped
public class FixtureAdvisor {

    private static final Logger LOG = Logger.getLogger(FixtureAdvisor.class);
    private static final String FIXTURE_PATH = "fixtures/wakeup-decisions.jsonl";
    private static final BigDecimal AIRCO_THRESHOLD = new BigDecimal("100.00");

    @Inject
    ShipmentPrepTool tool;

    private final Map<String, String> recordedCarriers = load();

    public void advise(Payment payment) {
        String carrier = recordedCarriers.getOrDefault(payment.orderId(), ruleCarrier(payment));
        LOG.infof("fixture advisor: orderId=%s carrier=%s (recorded=%s)", payment.orderId(), carrier,
                recordedCarriers.containsKey(payment.orderId()));
        tool.prepShipment(payment.orderId(), carrier);
    }

    private static String ruleCarrier(Payment payment) {
        return payment.amount().compareTo(AIRCO_THRESHOLD) >= 0 ? "AIRCO" : "FASTLINE";
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
