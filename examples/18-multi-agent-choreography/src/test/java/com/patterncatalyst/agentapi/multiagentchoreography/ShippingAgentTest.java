package com.patterncatalyst.agentapi.multiagentchoreography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.patterncatalyst.agentapi.Topics;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Exercises the shipping agent's reaction in isolation: given an {@code OrderPlaced} event on
 * {@code apa.orders.placed} (produced directly here, not through the order agent), it writes
 * {@code shipping.shipments} and publishes {@code ShipmentScheduled} on {@code
 * apa.shipping.scheduled}. MODEL=off (the {@code %test} profile forces {@code
 * shipping.agent.model-source=fixture}): these tests never call Ollama.
 */
@QuarkusTest
class ShippingAgentTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    DataSource dataSource;

    private void produceOrderPlaced(String orderId, String customerId, String amount) {
        var props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        String payload = """
                {"id":"%s","customerId":"%s","itemSku":"SKU-1","quantity":1,"amount":%s}
                """.formatted(orderId, customerId, amount);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(Topics.ORDERS_PLACED, orderId, payload)).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not produce OrderPlaced for " + orderId, e);
        }
    }

    private String shipmentCarrier(String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement select = c.prepareStatement(
                            "SELECT carrier FROM shipping.shipments WHERE order_id = ?")) {
                select.setString(1, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not read shipments for " + orderId, e);
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

    private List<ConsumerRecord<String, String>> drainShipmentScheduled(Duration wait) {
        var props = Map.<String, Object>of(
                "bootstrap.servers", bootstrapServers,
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest");
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.SHIPPING_SCHEDULED));
            long end = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    @Test
    void anOrderNamedInTheFixtureIsScheduledWithTheRecordedCarrierEvenWhenTheRuleWouldDisagree() {
        // O-9001 is recorded in fixtures/shipment-decisions.jsonl as AIRCO; the amount-based rule
        // alone would say FASTLINE (under 100.00). The fixture wins: this is the proof the
        // shipping agent actually reads the committed fixture, not just the fallback rule.
        produceOrderPlaced("O-9001", "CUST-9001", "49.99");

        assertEquals("AIRCO", shipmentCarrier("O-9001", Duration.ofSeconds(10)));
    }

    @Test
    void anOrderNotInTheFixtureFallsBackToTheAmountRuleAndPublishesShipmentScheduled() {
        produceOrderPlaced("O-8101", "CUST-8101", "149.99");
        produceOrderPlaced("O-8102", "CUST-8102", "19.99");

        assertEquals("AIRCO", shipmentCarrier("O-8101", Duration.ofSeconds(10)), "100.00 or more falls back to AIRCO");
        assertEquals("FASTLINE", shipmentCarrier("O-8102", Duration.ofSeconds(10)), "under 100.00 falls back to FASTLINE");

        var records = drainShipmentScheduled(Duration.ofSeconds(10));
        var published = records.stream().filter(r -> "O-8101".equals(r.key())).findFirst();
        assertTrue(published.isPresent(), "ShipmentScheduled keyed O-8101 on apa.shipping.scheduled");
        assertTrue(published.get().value().contains("\"customerId\":\"CUST-8101\""),
                "the published event carries customerId forward for the notification agent");
        assertTrue(published.get().value().contains("\"carrier\":\"AIRCO\""));
    }

    @Test
    void anOrderNeverPublishedHasNoShipmentRecord() {
        assertNull(shipmentCarrier("O-NEVER-PUBLISHED-SHIPPING", Duration.ofSeconds(2)));
    }
}
