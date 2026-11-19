package com.patterncatalyst.agentapi.multiagentchoreography;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Proves the whole choreography end to end, in process: one {@code POST /orders} call reaches
 * only the order agent; everything after that is the three agents reacting to Kafka events with
 * no orchestrator bean calling across {@code order}, {@code shipping} or {@code notification}.
 * MODEL=off throughout (the {@code %test} profile forces every {@code *.agent.model-source} to
 * {@code fixture}).
 */
@QuarkusTest
class ChoreographyEndToEndTest {

    @Inject
    DataSource dataSource;

    private static String order(String id, String customerId, String amount) {
        return """
                {"id":"%s","customerId":"%s","itemSku":"SKU-1","quantity":1,"amount":%s}
                """.formatted(id, customerId, amount);
    }

    private String oneColumn(String sql, String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement select = c.prepareStatement(sql)) {
                select.setString(1, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not read " + sql + " for " + orderId, e);
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
    void placingAnOrderOverHttpEventuallyRecordsACustomerNotification() {
        given().contentType("application/json").body(order("O-8301", "CUST-8301", "199.99"))
                .post("/orders").then().statusCode(202);

        // Hop 1: the order agent's own record.
        assertEquals("CUST-8301",
                oneColumn("SELECT customer_id FROM orders.orders WHERE id = ?", "O-8301", Duration.ofSeconds(10)));

        // Hop 2: the shipping agent reacted to OrderPlaced on its own, no method call from order.
        assertEquals("AIRCO",
                oneColumn("SELECT carrier FROM shipping.shipments WHERE order_id = ?", "O-8301", Duration.ofSeconds(15)),
                "100.00 or more falls back to AIRCO under MODEL=off");

        // Hop 3: the notification agent reacted to ShipmentScheduled on its own, the
        // choreography's terminal effect.
        assertEquals("ShipmentScheduled",
                oneColumn("SELECT event_type FROM notifications.notifications WHERE order_id = ?", "O-8301",
                        Duration.ofSeconds(15)));
    }
}
