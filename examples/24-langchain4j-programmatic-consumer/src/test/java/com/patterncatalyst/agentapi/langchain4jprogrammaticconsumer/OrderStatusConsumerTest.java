package com.patterncatalyst.agentapi.langchain4jprogrammaticconsumer;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole trigger-to-record path ({@link ConsumeOrderStatusRoute},
 * {@link ConsumerBean}, {@link FixtureConsumer}, {@link OrderStatusRecorder}) against Dev Services
 * PostgreSQL. MODEL=off (the {@code %test} profile forces {@code agent.consumer.model-source=fixture}):
 * these tests never call Ollama. {@link FixtureConsumer} still makes a real Streamable HTTP MCP
 * call, against {@link FixtureOrderStatusMcpServer}, the in-process stand-in for ch13 compiled into
 * this test build, so the {@code quarkus.langchain4j.mcp.apa-orders.*} wiring itself is exercised,
 * not just the fixture's own logic. The live, real-model round trip through
 * {@link OrderStatusConsumerAgent} and the real ch13 server is asserted by {@code verify.sh}
 * instead, since qwen3's tool calling is nondeterministic.
 */
@QuarkusTest
class OrderStatusConsumerTest {

    @Inject
    DataSource dataSource;

    private String columnFor(String column, String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement select = c.prepareStatement(
                            "SELECT " + column + " FROM consumer.agent_order_status_consultation WHERE order_id = ?")) {
                select.setString(1, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not read agent_order_status_consultation for " + orderId, e);
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

    @Test
    void consumingAnOrderCallsTheRemoteMcpToolDirectlyAndRecordsTheResult() {
        given().contentType("text/plain").body("O-TEST-1")
                .when().post("/consume")
                .then().statusCode(200);

        // Both the status and the tracking number are read out of the remote tool's own JSON result,
        // so the recorded tracking number proves the remote tool was actually reached.
        assertEquals("SHIPPED", columnFor("order_status", "O-TEST-1", Duration.ofSeconds(10)));
        assertEquals("FL-TEST-0001", columnFor("tracking_number", "O-TEST-1", Duration.ofSeconds(10)));
    }

    @Test
    void anOrderNeverConsumedHasNoRecord() {
        assertNull(columnFor("order_status", "O-NEVER-CONSUMED", Duration.ofSeconds(2)));
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
