package com.patterncatalyst.agentapi.identityandauthorizationguardrails;

import com.patterncatalyst.agentapi.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The one table this example owns: {@code orders.orders}. {@link #find} backs the read tool;
 * {@link #cancelAndRefund} is the one mutation, reached only after the write tool's scope check
 * and step-up acr check both clear.
 */
@ApplicationScoped
public class OrderRepository {

    @Inject
    DataSource dataSource;

    public OrderRow find(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT id, customer_id, item_sku, quantity, amount, status "
                                + "FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new OrderNotFoundException(orderId);
                }
                return new OrderRow(rs.getString("id"), rs.getString("customer_id"), rs.getString("item_sku"),
                        rs.getInt("quantity"), rs.getBigDecimal("amount"),
                        OrderStatus.valueOf(rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("order lookup failed for " + orderId, e);
        }
    }

    /** The refund is modeled as the order's own amount: this example's depth is identity and
     * authorization guardrails, not a second payment schema. */
    public void cancelAndRefund(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement update = c.prepareStatement(
                        "UPDATE orders.orders SET status = ? WHERE id = ?")) {
            update.setString(1, OrderStatus.CANCELLED.name());
            update.setString(2, orderId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("order cancellation failed for " + orderId, e);
        }
    }

    public record OrderRow(String id, String customerId, String itemSku, int quantity, BigDecimal amount,
            OrderStatus status) {

        /** Reuses the domain model's own lifecycle rule rather than re-deriving it here: CANCELLED
         * is reachable only from PLACED or PAID. */
        public boolean isCancellable() {
            return status.canMoveTo(OrderStatus.CANCELLED);
        }
    }
}
