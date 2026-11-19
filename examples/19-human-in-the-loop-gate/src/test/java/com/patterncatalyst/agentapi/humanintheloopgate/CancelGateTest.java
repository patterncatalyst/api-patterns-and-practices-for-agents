package com.patterncatalyst.agentapi.humanintheloopgate;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
 * Exercises the whole propose-hold-decide path against Dev Services Kafka and PostgreSQL.
 * MODEL=off (the {@code %test} profile forces {@code agent.cancelgate.model-source=fixture}):
 * these tests never call Ollama. The live, real-model round trip is asserted by {@code
 * verify.sh} instead, since qwen3's tool calling is nondeterministic; the order-existence and
 * cancellability checks run before the agent is ever called, so the 404/409 negative cases stay
 * model-independent on both paths.
 */
@QuarkusTest
class CancelGateTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    DataSource dataSource;

    private static Response requestCancel(String orderId, String reason) {
        String body = """
                {"reason":"%s"}
                """.formatted(reason);
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/orders/" + orderId + "/cancel-request");
    }

    private static String gateField(String orderId, String field) {
        return given().when().get("/orders/" + orderId + "/cancel-gate")
                .then().statusCode(200).extract().jsonPath().getString(field);
    }

    private void publishDecision(String correlationId, String decision) {
        var props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        String payload = """
                {"correlationId":"%s","decision":"%s","decidedBy":"reviewer@example.com"}
                """.formatted(correlationId, decision);
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(CancelGateTopics.DECISIONS, correlationId, payload)).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not produce decision for " + correlationId, e);
        }
    }

    private String orderStatus(String orderId) {
        return singleString("SELECT status FROM orders.orders WHERE id = ?", orderId);
    }

    private String paymentStatus(String orderId) {
        return singleString("SELECT status FROM payments.payments WHERE order_id = ?", orderId);
    }

    private int executionCount(String correlationId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT COUNT(*) FROM orders.cancel_execution_log WHERE correlation_id = ?")) {
            select.setString(1, correlationId);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("execution-log lookup failed for " + correlationId, e);
        }
    }

    private String singleString(String sql, String param) {
        try (Connection c = dataSource.getConnection(); PreparedStatement select = c.prepareStatement(sql)) {
            select.setString(1, param);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private String waitForGateStatus(String orderId, String expected, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        String last = null;
        while (System.nanoTime() < end) {
            last = gateField(orderId, "status");
            if (expected.equals(last)) {
                return last;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void anOrderNamedInTheFixtureIsClassifiedWithTheRecordedReasonCodeEvenWhenTheRuleWouldDisagree() {
        // O-19101's reason text names neither keyword, so the rule alone would say
        // CUSTOMER_REQUEST; the fixture records FRAUD_SUSPECTED. The fixture wins: proof the
        // classification is actually read from fixtures/cancel-reason-overrides.jsonl, not just
        // the fallback rule.
        requestCancel("O-19101", "customer changed their mind").then().statusCode(202);
        assertEquals("FRAUD_SUSPECTED", gateField("O-19101", "reasonCode"));
    }

    @Test
    void anOrderNotInTheFixtureFallsBackToTheKeywordRule() {
        requestCancel("O-19103", "this looks like fraud to me").then().statusCode(202);
        assertEquals("FRAUD_SUSPECTED", gateField("O-19103", "reasonCode"));

        requestCancel("O-19104", "duplicate order placed twice by mistake").then().statusCode(202);
        assertEquals("DUPLICATE_ORDER", gateField("O-19104", "reasonCode"));

        requestCancel("O-19105", "wrong size, please cancel").then().statusCode(202);
        assertEquals("CUSTOMER_REQUEST", gateField("O-19105", "reasonCode"));
    }

    @Test
    void theTriggerPausesAtTheGateWithNoSideEffectYet() {
        requestCancel("O-19001", "customer requested cancellation").then().statusCode(202);

        assertEquals("PENDING", gateField("O-19001", "status"));
        assertEquals("PLACED", orderStatus("O-19001"));
        assertEquals("COMPLETED", paymentStatus("O-19001"));
    }

    @Test
    void anApprovedDecisionAppliesTheSideEffectExactlyOnceEvenWhenRedelivered() {
        // A dedicated order (O-19006), separate from O-19001's pause-only test, so JUnit's
        // unspecified method order never lets one test observe another test's side effect on
        // the same order.
        requestCancel("O-19006", "customer requested cancellation").then().statusCode(202);
        String correlationId = gateField("O-19006", "correlationId");

        publishDecision(correlationId, "APPROVE");
        assertEquals("APPROVED_EXECUTED", waitForGateStatus("O-19006", "APPROVED_EXECUTED", Duration.ofSeconds(10)));
        assertEquals("CANCELLED", orderStatus("O-19006"));
        assertEquals("REFUNDED", paymentStatus("O-19006"));
        assertEquals(1, executionCount(correlationId));

        // Redelivery: the exact same APPROVE, consumed a second time, must not re-execute
        // anything. By the time this runs, the first APPROVE already committed (the poll above
        // waited for it), so a brief fixed wait is enough to let the consumer process (and
        // ignore) the duplicate.
        publishDecision(correlationId, "APPROVE");
        sleepQuietly(Duration.ofSeconds(5));
        assertEquals("CANCELLED", orderStatus("O-19006"));
        assertEquals("REFUNDED", paymentStatus("O-19006"));
        assertEquals(1, executionCount(correlationId));
    }

    @Test
    void aRejectedDecisionTerminatesWithNoSideEffect() {
        requestCancel("O-19002", "customer requested cancellation").then().statusCode(202);
        String correlationId = gateField("O-19002", "correlationId");

        publishDecision(correlationId, "REJECT");
        assertEquals("REJECTED_BY_USER", waitForGateStatus("O-19002", "REJECTED_BY_USER", Duration.ofSeconds(10)));
        assertEquals("PLACED", orderStatus("O-19002"));
        assertEquals("COMPLETED", paymentStatus("O-19002"));
        assertEquals(0, executionCount(correlationId));
    }

    @Test
    void anUnknownOrderIsRejectedWithoutEverOpeningAGate() {
        requestCancel("O-NEVER-PLACED", "cancel it").then().statusCode(404);
        given().when().get("/orders/O-NEVER-PLACED/cancel-gate").then().statusCode(404);
    }

    @Test
    void anAlreadyCancelledOrderIsRejectedWithoutEverOpeningAGate() {
        requestCancel("O-19003", "cancel again").then().statusCode(409);
        given().when().get("/orders/O-19003/cancel-gate").then().statusCode(404);
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
