package com.patterncatalyst.agentapi.consumingtheasyncside;

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
 * Exercises the whole trigger-to-record path ({@link ShipmentDeliveredRoute},
 * {@link NotificationBean}, {@link FixtureComposer}, {@link NotificationRecorder}) against Dev
 * Services Kafka and PostgreSQL. MODEL=off (the {@code %test} profile forces
 * {@code agent.notification.model-source=fixture}): these tests never call Ollama, so
 * {@code mvn -B verify} and CI stay model-free. The live, real-model round trip through
 * {@link NotificationComposerAgent} is asserted by {@code verify.sh} instead, since qwen3's
 * composed prose is nondeterministic; every assertion here is on event-derived structured fields,
 * never on message text.
 */
@QuarkusTest
class ShipmentDeliveredConsumerTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    DataSource dataSource;

    private void produceShipmentDelivered(String orderId, String customerId, String carrier, String trackingNumber) {
        var props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        String payload = """
                {"orderId":"%s","customerId":"%s","carrier":"%s","trackingNumber":"%s"}
                """.formatted(orderId, customerId, carrier, trackingNumber);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(Topics.SHIPPING_DELIVERED, orderId, payload)).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not produce ShipmentDelivered for " + orderId, e);
        }
    }

    private String customerIdFor(String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement select = c.prepareStatement(
                            "SELECT customer_id FROM notify.notifications "
                                    + "WHERE order_id = ? AND event_type = ?")) {
                select.setString(1, orderId);
                select.setString(2, NotificationBean.SHIPMENT_DELIVERED_EVENT_TYPE);
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

    private long rowCount(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT count(*) FROM notify.notifications WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not count notifications for " + orderId, e);
        }
    }

    @Test
    void aDeliveredEventRecordsOneNotificationWithTheEventDerivedFields() {
        produceShipmentDelivered("O-8001", "CUST-8001", "AIRCO", "AIR-778821");

        assertEquals("CUST-8001", customerIdFor("O-8001", Duration.ofSeconds(10)));
        assertEquals(1L, rowCount("O-8001"));
    }

    @Test
    void redeliveringTheSameEventStaysAtExactlyOneRow() throws InterruptedException {
        produceShipmentDelivered("O-8002", "CUST-8002", "FASTLINE", "FL-990011");
        assertEquals("CUST-8002", customerIdFor("O-8002", Duration.ofSeconds(10)));

        // Redelivery: the same order id and event type, exactly as a consumer group rebalance or an
        // at-least-once producer retry would replay it.
        produceShipmentDelivered("O-8002", "CUST-8002", "FASTLINE", "FL-990011");
        // Give the duplicate a fair chance to be consumed and processed before asserting the count
        // never moved past one.
        Thread.sleep(3000);

        assertEquals(1L, rowCount("O-8002"));
    }

    @Test
    void anOrderNeverDeliveredHasNoNotification() {
        assertNull(customerIdFor("O-NEVER-DELIVERED", Duration.ofSeconds(2)));
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
