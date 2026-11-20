package com.patterncatalyst.agentapi.auditingaiusingcode;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole trigger-to-audit-to-record path ({@link TriggerRoute}, {@link ConsumerBean},
 * {@link AuditedToolCaller}, {@link OutboundCallResultRecorder}) against Dev Services Kafka and
 * PostgreSQL. MODEL=off (the {@code %test} profile forces {@code agent.outbound.model-source=fixture}):
 * these tests never call Ollama, but {@link AuditedToolCaller} still makes a real Streamable HTTP
 * MCP call, against {@link FixtureOrderStatusMcpServer}, the in-process stand-in for ch13 compiled
 * into this test build, so the audit-then-call ordering and the {@code outbound.mcp.target-url}
 * wiring are both actually exercised, not just the recorder's own logic. The live, real-model round
 * trip through {@link OutboundCallAgent} and the real ch13 server is asserted by {@code verify.sh}
 * instead, since qwen3's tool calling is nondeterministic.
 */
@QuarkusTest
class OutboundCallAuditTest {

    @Inject
    DataSource dataSource;

    private String resultColumn(String column, String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement select = c.prepareStatement(
                            "SELECT " + column + " FROM consumer.outbound_call_result WHERE order_id = ?")) {
                select.setString(1, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not read outbound_call_result for " + orderId, e);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<java.util.Map<String, Object>> auditLogFor(String correlationId) {
        return given().when().get("/audit/log/" + correlationId)
                .then().statusCode(200)
                .extract().body().jsonPath().getList("$");
    }

    private static void consume(String orderId) {
        given().contentType("text/plain").body(orderId)
                .when().post("/consume")
                .then().statusCode(200);
    }

    @Test
    void consumingAnOrderAppendsAnAuditRecordBeforeTheCallAndRecordsTheBusinessResult() {
        consume("O-TEST-1");

        // The business row proves the remote tool was actually reached: the tracking number comes
        // only from the remote tool's own JSON result.
        assertEquals("SHIPPED", resultColumn("order_status", "O-TEST-1", Duration.ofSeconds(10)));
        assertEquals("FL-TEST-0001", resultColumn("tracking_number", "O-TEST-1", Duration.ofSeconds(10)));

        List<java.util.Map<String, Object>> rows = auditLogFor("O-TEST-1");
        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertEquals("OUTBOUND", row.get("direction"));
        assertEquals("orderStatus", row.get("target"));
        assertEquals("O-TEST-1", row.get("correlationId"));
        assertTrue(row.get("argumentDigest").toString().length() == 64, "argumentDigest must be a SHA-256 hex digest");
    }

    @Test
    void replayReproducesTheLiveProjectionAfterNormalAppends() {
        consume("O-TEST-2");

        given().when().post("/audit/replay")
                .then().statusCode(200)
                .body("matchesLive", org.hamcrest.Matchers.is(true))
                .body("recordsReplayed", org.hamcrest.Matchers.greaterThanOrEqualTo(1));
    }

    @Test
    void mutationIsRejectedByDefault() {
        consume("O-TEST-3");
        String id = auditLogFor("O-TEST-3").get(0).get("id").toString();
        String originalDigest = auditLogFor("O-TEST-3").get(0).get("argumentDigest").toString();

        String body = """
                {"id":"%s","newArgumentDigest":"tampered"}
                """.formatted(id);
        given().contentType(ContentType.JSON).body(body)
                .when().post("/audit/demo/mutate")
                .then().statusCode(403);

        var row = auditLogFor("O-TEST-3").get(0);
        assertEquals(originalDigest, row.get("argumentDigest"), "a rejected mutate must not have changed the row");
    }

    @Test
    void anOrderNeverConsumedHasNoAuditRowAndNoBusinessRow() {
        assertEquals(0, auditLogFor("O-NEVER-CONSUMED").size());
        assertEquals(null, resultColumn("order_status", "O-NEVER-CONSUMED", Duration.ofSeconds(2)));
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
