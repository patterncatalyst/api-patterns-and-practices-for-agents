package com.patterncatalyst.agentapi.tokenefficientresponsebff;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The one CDI read service both the REST BFF endpoint and the GraphQL {@code @Query} share, so
 * the lean projection is computed in one place, not reimplemented twice and left to drift.
 * {@link #summary} runs its own narrow SQL query selecting only the lean columns: it is a
 * purpose-built projection, not {@link #full}'s result reshaped in Java after the fact.
 */
@ApplicationScoped
public class OrderReadService {

    private static final String FULL_COLUMNS = "id, record_uuid, customer_uuid, customer_id, "
            + "customer_full_name, customer_email, customer_risk_score, item_sku, quantity, amount, "
            + "status, internal_notes, audit_trail";
    private static final String SUMMARY_COLUMNS = "id, customer_id, item_sku, quantity, amount, status";

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    /** The full human payload: every column, including the heavy and internal ones. */
    public OrderFull full(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select =
                        c.prepareStatement("SELECT " + FULL_COLUMNS + " FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("no order with id " + orderId);
                }
                CustomerRecord customer = new CustomerRecord(
                        (UUID) rs.getObject("customer_uuid"),
                        rs.getString("customer_id"),
                        rs.getString("customer_full_name"),
                        rs.getString("customer_email"),
                        rs.getInt("customer_risk_score"));
                return new OrderFull(
                        (UUID) rs.getObject("record_uuid"),
                        rs.getString("id"),
                        customer,
                        rs.getString("item_sku"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        rs.getString("internal_notes"),
                        readAuditTrail(rs.getString("audit_trail")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("order lookup failed for " + orderId, e);
        }
    }

    /** The lean BFF projection, read directly from its own narrow query. */
    public OrderSummary summary(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select =
                        c.prepareStatement("SELECT " + SUMMARY_COLUMNS + " FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("no order with id " + orderId);
                }
                return new OrderSummary(rs.getString("id"), rs.getString("customer_id"), rs.getString("item_sku"),
                        rs.getInt("quantity"), rs.getBigDecimal("amount"), rs.getString("status"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("order summary lookup failed for " + orderId, e);
        }
    }

    private List<AuditEntry> readAuditTrail(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<AuditEntry>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("audit trail parse failed", e);
        }
    }
}
