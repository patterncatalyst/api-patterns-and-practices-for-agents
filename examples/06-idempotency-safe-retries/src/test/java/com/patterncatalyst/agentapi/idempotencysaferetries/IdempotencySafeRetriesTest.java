package com.patterncatalyst.agentapi.idempotencysaferetries;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.patterncatalyst.agentapi.Topics;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
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
class IdempotencySafeRetriesTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    private List<ConsumerRecord<String, String>> drain(Duration wait) {
        var props = Map.<String, Object>of(
                "bootstrap.servers", bootstrapServers,
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest");
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.ORDERS_PLACED));
            long end = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private Response placeOrder(String idempotencyKey, String body) {
        return given().contentType("application/json").header("Idempotency-Key", idempotencyKey).body(body)
                .post("/orders");
    }

    @Test
    void twoCallsWithTheSameKeyAndBodyYieldOneOrderOneEventAndAnIdenticalResponse() {
        String body = """
                {"customerId":"CUST-1","itemSku":"SKU-1","quantity":2,"amount":49.99}
                """;

        Response first = placeOrder("idem-key-same", body);
        first.then().statusCode(201);
        String firstBody = first.asString();
        String orderId = first.jsonPath().getString("orderId");

        Response second = placeOrder("idem-key-same", body);
        second.then().statusCode(201);
        String secondBody = second.asString();

        assertEquals(firstBody, secondBody, "a replayed call returns the identical response body");

        var records = drain(Duration.ofSeconds(10));
        var published = records.stream().filter(r -> orderId.equals(r.key())).toList();
        assertEquals(1, published.size(), "exactly one OrderPlaced record keyed by " + orderId);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejectedWithConflict() {
        String firstBody = """
                {"customerId":"CUST-2","itemSku":"SKU-2","quantity":1,"amount":10.00}
                """;
        String differentBody = """
                {"customerId":"CUST-2","itemSku":"SKU-9","quantity":1,"amount":10.00}
                """;

        placeOrder("idem-key-conflict", firstBody).then().statusCode(201);
        placeOrder("idem-key-conflict", differentBody).then().statusCode(409)
                .contentType("application/problem+json");
    }

    @Test
    void missingIdempotencyKeyIsRejectedWith400() {
        given().contentType("application/json").body("""
                {"customerId":"CUST-3","itemSku":"SKU-1","quantity":1,"amount":5.00}
                """)
                .post("/orders")
                .then().statusCode(400).contentType("application/problem+json");
    }

    @Test
    void invalidOrderIsRejectedWith400() {
        placeOrder("idem-key-invalid", """
                {"customerId":"CUST-4","itemSku":"SKU-1","quantity":0,"amount":5.00}
                """)
                .then().statusCode(400).contentType("application/problem+json");
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
