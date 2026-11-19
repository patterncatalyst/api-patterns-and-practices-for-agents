package com.patterncatalyst.agentapi.eventtriggeredagentwakeup;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.patterncatalyst.agentapi.Topics;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole trigger-to-tool path (Kafka consumer, {@link AgentWakeupBean},
 * {@link FixtureAdvisor}, {@link ShipmentPrepTool}) against Dev Services Kafka and PostgreSQL.
 * MODEL=off (the {@code %test} profile forces {@code agent.wakeup.model-source=fixture}): these
 * tests never call Ollama, so {@code mvn -B verify} and CI stay model-free. The live, real-model
 * round trip through {@link ShipmentAdvisor} is asserted by {@code verify.sh} instead, since
 * qwen3's tool calling is nondeterministic.
 */
@QuarkusTest
class PaymentWakeupTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    DataSource dataSource;

    private void producePaymentProcessed(String paymentId, String orderId, String amount) {
        var props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        String payload = """
                {"id":"%s","orderId":"%s","amount":%s,"status":"COMPLETED"}
                """.formatted(paymentId, orderId, amount);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(Topics.PAYMENTS_PROCESSED, orderId, payload)).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not produce PaymentProcessed for " + orderId, e);
        }
    }

    private String carrierFor(String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement select = c.prepareStatement(
                            "SELECT carrier FROM shipping.shipment_prep WHERE order_id = ?")) {
                select.setString(1, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not read shipment_prep for " + orderId, e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    @Test
    void anOrderNamedInTheFixtureIsRecordedWithTheRecordedCarrierEvenWhenTheRuleWouldDisagree() {
        // O-9001 is recorded in fixtures/wakeup-decisions.jsonl as AIRCO; the amount-based rule
        // alone would say FASTLINE (under 100.00). The fixture wins: this is the proof the
        // agent step actually reads the committed fixture, not just the fallback rule.
        producePaymentProcessed("PAY-9001", "O-9001", "49.99");

        assertEquals("AIRCO", carrierFor("O-9001", Duration.ofSeconds(10)));
    }

    @Test
    void anOrderNotInTheFixtureFallsBackToTheAmountRule() {
        producePaymentProcessed("PAY-9002", "O-9002", "149.99");
        producePaymentProcessed("PAY-9003", "O-9003", "19.99");

        assertEquals("AIRCO", carrierFor("O-9002", Duration.ofSeconds(10)), "100.00 or more falls back to AIRCO");
        assertEquals("FASTLINE", carrierFor("O-9003", Duration.ofSeconds(10)), "under 100.00 falls back to FASTLINE");
    }

    @Test
    void anOrderNeverPublishedHasNoShipmentPrepRecord() {
        assertNull(carrierFor("O-NEVER-PUBLISHED", Duration.ofSeconds(2)));
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
