package com.patterncatalyst.agentapi.intentendpoint;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.patterncatalyst.agentapi.Topics;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
class IntentEndpointTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    private List<ConsumerRecord<String, String>> drain(Duration wait) {
        var props = Map.<String, Object>of(
                "bootstrap.servers", bootstrapServers,
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest");
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.ORDERS_CANCELLED));
            long end = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    @Test
    void cancelUnknownOrderIsRejectedWith404() {
        given().contentType("application/json").body("""
                {"reason":"customer changed mind"}
                """)
                .post("/orders/O-DOES-NOT-EXIST/cancel-and-refund")
                .then().statusCode(404);
    }

    @Test
    void cancelAndRefundFlipsBothAndPublishesThenRejectsDoubleCancel() {
        given().contentType("application/json").body("""
                {"reason":"customer changed mind"}
                """)
                .post("/orders/O-5001/cancel-and-refund")
                .then().statusCode(200)
                .body("orderId", org.hamcrest.Matchers.equalTo("O-5001"))
                .body("reason", org.hamcrest.Matchers.equalTo("customer changed mind"));

        var records = drain(Duration.ofSeconds(10));
        var published = records.stream().filter(r -> "O-5001".equals(r.key())).toList();
        assertEquals(1, published.size(), "exactly one OrderCancelled record keyed by O-5001");
        assertTrue(published.get(0).value().contains("\"orderId\":\"O-5001\""));

        // The order is already CANCELLED: a second call must not re-run the saga.
        given().contentType("application/json").body("""
                {"reason":"second attempt"}
                """)
                .post("/orders/O-5001/cancel-and-refund")
                .then().statusCode(409);
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
