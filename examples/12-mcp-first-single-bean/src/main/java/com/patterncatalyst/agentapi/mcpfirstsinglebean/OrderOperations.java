package com.patterncatalyst.agentapi.mcpfirstsinglebean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The bridge this chapter is about: one CDI bean, one business method, read once from
 * {@code orders.orders}. {@link OrderResource}, {@link OrderGraphQLApi} and
 * {@link OrderMcpTools} are three faces over this single method; none of them computes
 * {@code OrderSummary} on its own. Change the query here once, and all three faces change
 * together. This example has nothing to write, so there is no Camel route: a read has no event
 * to publish.
 */
@ApplicationScoped
public class OrderOperations {

    @Inject
    DataSource dataSource;

    public OrderSummary orderSummary(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT id, customer_id, item_sku, quantity, amount, status "
                                + "FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new OrderNotFoundException(orderId);
                }
                return new OrderSummary(rs.getString("id"), rs.getString("customer_id"), rs.getString("item_sku"),
                        rs.getInt("quantity"), rs.getBigDecimal("amount"), rs.getString("status"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("order summary lookup failed for " + orderId, e);
        }
    }
}
