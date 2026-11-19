package com.patterncatalyst.agentapi.instructiveerror;

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

    @Transactional
    public PlaceOrderResult placeOrder(OrderCreateRequest request) {
        Order order;
        try {
            // The domain Order record's own compact constructor validates the fields (non-blank
            // text, positive quantity, positive two-decimal amount) and throws
            // IllegalArgumentException with a message of the form "<field> <problem>". That is
            // rewrapped here as an OrderValidationException naming the offending field, so
            // OrderExceptionMappers can put it in the 400's remedy.
            order = new Order("O-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    request.customerId(), request.itemSku(), request.quantity(), request.amount());
        } catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            throw new OrderValidationException(message.split(" ", 2)[0], message);
        }
        try (Connection c = dataSource.getConnection()) {
            insert(c, order);
        } catch (SQLException e) {
            throw new IllegalStateException("place-order failed", e);
        }
        OrderResponse response = toResponse(order, OrderStatus.PLACED);
        OrderPlacedEvent event = new OrderPlacedEvent(order.id(), order.customerId(), order.itemSku(),
                order.quantity(), order.amount(), Instant.now().toString());
        return new PlaceOrderResult(response, event);
    }

    public OrderResponse getOrder(String orderId) {
        try (Connection c = dataSource.getConnection()) {
            return readOrder(c, orderId);
        } catch (SQLException e) {
            throw new IllegalStateException("get-order failed for " + orderId, e);
        }
    }

    @Transactional
    public CancelOrderResult cancelOrder(String orderId) {
        try (Connection c = dataSource.getConnection()) {
            OrderStatus current = currentStatus(c, orderId);
            if (current == null) {
                throw new OrderNotFoundException(orderId);
            }
            if (!current.canMoveTo(OrderStatus.CANCELLED)) {
                throw new OrderNotCancellableException(orderId, current);
            }
            updateStatus(c, orderId, OrderStatus.CANCELLED);
            OrderResponse response = readOrder(c, orderId);
            OrderCancelledEvent event = new OrderCancelledEvent(orderId, Instant.now().toString());
            return new CancelOrderResult(response, event);
        } catch (SQLException e) {
            throw new IllegalStateException("cancel-order failed for " + orderId, e);
        }
    }

    private OrderResponse readOrder(Connection c, String orderId) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(
                "SELECT id, customer_id, item_sku, quantity, amount, status FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new OrderNotFoundException(orderId);
                }
                return new OrderResponse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getString(6));
            }
        }
    }

    private OrderStatus currentStatus(Connection c, String orderId) throws SQLException {
        try (PreparedStatement select = c.prepareStatement("SELECT status FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? OrderStatus.valueOf(rs.getString(1)) : null;
            }
        }
    }

    private void updateStatus(Connection c, String orderId, OrderStatus status) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE orders.orders SET status = ? WHERE id = ?")) {
            update.setString(1, status.name());
            update.setString(2, orderId);
            update.executeUpdate();
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
