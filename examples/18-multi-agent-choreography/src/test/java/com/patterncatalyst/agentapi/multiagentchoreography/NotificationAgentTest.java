package com.patterncatalyst.agentapi.multiagentchoreography;

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
 * Exercises the notification agent's reaction in isolation: given a {@code ShipmentScheduled}
 * event on {@code apa.shipping.scheduled} (produced directly here, not through the shipping
 * agent), it writes {@code notifications.notifications} and publishes nothing further, the
 * choreography's terminal step. MODEL=off (the {@code %test} profile forces {@code
 * notification.agent.model-source=fixture}): these tests never call Ollama.
 */
@QuarkusTest
class NotificationAgentTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    DataSource dataSource;

    private void produceShipmentScheduled(String orderId, String customerId, String carrier, String trackingNumber) {
        var props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        String payload = """
                {"orderId":"%s","customerId":"%s","carrier":"%s","trackingNumber":"%s"}
                """.formatted(orderId, customerId, carrier, trackingNumber);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(Topics.SHIPPING_SCHEDULED, orderId, payload)).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not produce ShipmentScheduled for " + orderId, e);
        }
    }

    private String notificationEventType(String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement select = c.prepareStatement(
                            "SELECT event_type FROM notifications.notifications WHERE order_id = ?")) {
                select.setString(1, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not read notifications for " + orderId, e);
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
    void aScheduledShipmentIsRecordedAsANotification() {
        produceShipmentScheduled("O-8201", "CUST-8201", "FASTLINE", "TRK-O-8201");

        assertEquals("ShipmentScheduled", notificationEventType("O-8201", Duration.ofSeconds(10)));
    }

    @Test
    void anEventNeverPublishedHasNoNotificationRecord() {
        assertNull(notificationEventType("O-NEVER-PUBLISHED-NOTIFICATION", Duration.ofSeconds(2)));
    }
}
