package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.patterncatalyst.agentapi.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The order server's only reads: both touch {@code orders.orders} alone, never
 * {@code shipping.shipments}. Keeping the two schemas apart, even though both are reachable from
 * the same JDBC connection pool, is the point of a domain-split server: an order server that
 * reaches into the shipping schema is no longer split from it.
 */
@ApplicationScoped
public class OrderReads {

    @Inject
    DataSource dataSource;

    public OrderOwnStatus status(String orderId) {
        OrderRow row = orderRow(orderId);
        return new OrderOwnStatus(row.id(), row.customerId(), row.itemSku(), row.quantity(), row.amount(), row.status());
    }

    public CancelEligibility cancelEligibility(String orderId) {
        OrderRow row = orderRow(orderId);
        return new CancelEligibility(row.id(), row.status(), row.status().canMoveTo(OrderStatus.CANCELLED));
    }

    private OrderRow orderRow(String orderId) {
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
                        rs.getInt("quantity"), rs.getBigDecimal("amount"), OrderStatus.valueOf(rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("order lookup failed for " + orderId, e);
        }
    }

    private record OrderRow(String id, String customerId, String itemSku, int quantity, java.math.BigDecimal amount,
            OrderStatus status) {
    }
}
