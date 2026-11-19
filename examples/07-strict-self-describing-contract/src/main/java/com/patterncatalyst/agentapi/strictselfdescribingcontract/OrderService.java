package com.patterncatalyst.agentapi.strictselfdescribingcontract;

import com.patterncatalyst.agentapi.Order;
import com.patterncatalyst.agentapi.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/** Writes and reads the one table this example owns: {@code orders.orders}. */
@ApplicationScoped
public class OrderService {

    @Inject
    DataSource dataSource;

    // orders.amount is numeric(12, 2): at most ten integer digits before the two decimal places.
    // A larger value would overflow the column, so it is rejected here as an invalid request
    // rather than left to surface as a database error.
    private static final java.math.BigDecimal MAX_AMOUNT = new java.math.BigDecimal("9999999999.99");

    @Transactional
    public PlaceOrderResult placeOrder(OrderCreateRequest request) {
        // Reject a missing required field as an invalid request here, so it becomes a documented 400
        // instead of a null reaching the domain record's own null check and surfacing as a 500.
        requireText(request.customerId(), "customerId");
        requireText(request.itemSku(), "itemSku");
        if (request.amount() != null && request.amount().compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("amount must not exceed " + MAX_AMOUNT);
        }
        try (Connection c = dataSource.getConnection()) {
            String orderId = "O-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            // The domain Order record's own compact constructor validates the fields (non-blank
            // text, positive quantity, positive two-decimal amount), so a bad request fails here
            // before anything is written.
            Order order = new Order(orderId, request.customerId(), request.itemSku(), request.quantity(),
                    request.amount());
            insert(c, order);
            OrderResponse response = toResponse(order, OrderStatus.PLACED);
            OrderPlacedEvent event = new OrderPlacedEvent(order.id(), order.customerId(), order.itemSku(),
                    order.quantity(), order.amount(), Instant.now().toString());
            return new PlaceOrderResult(response, event);
        } catch (SQLException e) {
            throw new IllegalStateException("place-order failed", e);
        }
    }

    public OrderResponse getOrder(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT id, customer_id, item_sku, quantity, amount, status FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new OrderNotFoundException("no order with id " + orderId);
                }
                return new OrderResponse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getString(6));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("get-order failed for " + orderId, e);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private void insert(Connection c, Order order) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, order.id());
            insert.setString(2, order.customerId());
            insert.setString(3, order.itemSku());
            insert.setInt(4, order.quantity());
            insert.setBigDecimal(5, order.amount());
            insert.setString(6, OrderStatus.PLACED.name());
            insert.executeUpdate();
        }
    }

    private static OrderResponse toResponse(Order order, OrderStatus status) {
        return new OrderResponse(order.id(), order.customerId(), order.itemSku(), order.quantity(), order.amount(),
                status.name());
    }
}
