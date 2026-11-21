package com.patterncatalyst.agentapi.afdeterministicdecisioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole agent-facing path, {@link RefundReviewTool#reviewRefund} through
 * {@link RefundReviewer} to {@link ReviewRecorder}, against Dev Services PostgreSQL: the
 * compliant proposal is recorded APPROVE, the violating proposal is recorded REJECT naming
 * {@code amount-exceeds-paid}. No model is ever on this path, and the rule engine is
 * deterministic by construction, so {@code mvn -B verify} and CI need nothing model-shaped at
 * all. {@code RefundReviewRulesTest} proves the ruleset itself, including the weakened-ruleset
 * negative witness, with no container involved; this test proves the tool wires that ruleset to a
 * durable, observable record.
 */
@QuarkusTest
class RefundReviewToolTest {

    @Inject
    RefundReviewTool tool;

    @Inject
    DataSource dataSource;

    private String columnFor(String column, String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT " + column + " FROM refunds.reviews WHERE order_id = ? "
                                + "ORDER BY reviewed_at DESC LIMIT 1")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read reviews for " + orderId, e);
        }
    }

    @Test
    void theCompliantProposalIsApprovedAndRecorded() {
        String json = tool.reviewRefund("O-AF1001", "CUST-AF1001", "PAID",
                new BigDecimal("200.00"), new BigDecimal("150.00"), "DAMAGED_IN_TRANSIT");

        assertTrue(json.contains("\"decision\":\"APPROVE\""));
        assertEquals("APPROVE", columnFor("verdict", "O-AF1001"));
        assertEquals(null, columnFor("failed_clause", "O-AF1001"));
    }

    @Test
    void theViolatingProposalIsRejectedAndRecordedWithTheAmountClause() {
        String json = tool.reviewRefund("O-AF1002", "CUST-AF1002", "PAID",
                new BigDecimal("200.00"), new BigDecimal("500.00"), "DAMAGED_IN_TRANSIT");

        assertTrue(json.contains("\"decision\":\"REJECT\""));
        assertTrue(json.contains("amount-exceeds-paid"));
        assertEquals("REJECT", columnFor("verdict", "O-AF1002"));
        assertEquals("amount-exceeds-paid", columnFor("failed_clause", "O-AF1002"));
    }
}
