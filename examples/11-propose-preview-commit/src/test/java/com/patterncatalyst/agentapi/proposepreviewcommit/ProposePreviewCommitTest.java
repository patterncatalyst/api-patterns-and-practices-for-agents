package com.patterncatalyst.agentapi.proposepreviewcommit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
class ProposePreviewCommitTest {

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

    private long countForKey(List<ConsumerRecord<String, String>> records, String key) {
        return records.stream().filter(r -> key.equals(r.key())).count();
    }

    @Test
    void cancelUnknownOrderIsRejectedWith404() {
        given().contentType("application/json").body("{}")
                .post("/orders/O-DOES-NOT-EXIST/cancel")
                .then().statusCode(404);
    }

    @Test
    void dryRunReviewsWithoutPersistingOrPublishingThenCommitSucceeds() {
        given().contentType("application/json").body("""
                {"reason":"checking the refund amount first","dryRun":true}
                """)
                .post("/orders/O-5001/cancel")
                .then().statusCode(200)
                .body("orderId", equalTo("O-5001"))
                .body("beforeStatus", equalTo("PLACED"))
                .body("afterStatus", equalTo("CANCELLED"))
                .body("refundAmount", equalTo(149.00f))
                .body("wouldEmitEvents", org.hamcrest.Matchers.contains("OrderCancelled"))
                .body("reversible", equalTo(false));

        // The dry run must have written nothing and published nothing: the real commit that
        // follows must still succeed (a cancelled order would reject a second cancel with 409).
        given().contentType("application/json").body("""
                {"reason":"customer changed mind"}
                """)
                .post("/orders/O-5001/cancel")
                .then().statusCode(200)
                .body("orderId", equalTo("O-5001"));

        var published = drain(Duration.ofSeconds(10));
        assertEquals(1, countForKey(published, "O-5001"), "exactly one OrderCancelled record keyed by O-5001");
    }

    @Test
    void rejectedByUserIsGracefulNoOpThenCommitStillSucceeds() {
        given().contentType("application/json").body("""
                {"decision":"REJECTED_BY_USER"}
                """)
                .post("/orders/O-5002/cancel")
                .then().statusCode(200)
                .body("orderId", equalTo("O-5002"))
                .body("decision", equalTo("REJECTED_BY_USER"))
                .body("message", equalTo("no action taken"));

        // A REJECTED_BY_USER decision must have written nothing: the order is still cancellable.
        given().contentType("application/json").body("{}")
                .post("/orders/O-5002/cancel")
                .then().statusCode(200)
                .body("orderId", equalTo("O-5002"));

        var published = drain(Duration.ofSeconds(10));
        assertEquals(1, countForKey(published, "O-5002"), "exactly one OrderCancelled record, from the commit only");
    }

    @Test
    void plainCommitPublishesExactlyOneEvent() {
        given().contentType("application/json").body("{}")
                .post("/orders/O-5003/cancel")
                .then().statusCode(200)
                .body("orderId", equalTo("O-5003"));

        var published = drain(Duration.ofSeconds(10));
        assertEquals(1, countForKey(published, "O-5003"), "exactly one OrderCancelled record keyed by O-5003");
    }

    @Test
    void cancellingAnAlreadyCancelledOrderIsRejectedWith409ForBothDryRunAndCommit() {
        given().contentType("application/json").body("""
                {"dryRun":true}
                """)
                .post("/orders/O-5004/cancel")
                .then().statusCode(409);

        given().contentType("application/json").body("{}")
                .post("/orders/O-5004/cancel")
                .then().statusCode(409);
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
