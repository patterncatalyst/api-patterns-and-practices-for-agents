package com.patterncatalyst.agentapi.intentendpoint;

import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.PaymentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * The cancel-and-refund saga: one order and its payment move together, in one database
 * transaction, or neither moves. This is the atomicity guard the chapter's negative witness
 * checks for: a chatty CRUD caller that updates the order and the payment as two separate
 * calls has a window where one has moved and the other has not; this saga never does.
 */
@ApplicationScoped
public class CancelRefundSaga {

    @Inject
    DataSource dataSource;

    @Transactional
    public OrderCancelledEvent cancelAndRefund(String orderId, String reason) {
        try (Connection c = dataSource.getConnection()) {
            OrderStatus status = currentOrderStatus(c, orderId);
            if (status == null) {
                throw new OrderNotFoundException("order " + orderId + " does not exist");
            }
            if (status != OrderStatus.PLACED && status != OrderStatus.PAID) {
                throw new OrderNotCancellableException(
                        "order " + orderId + " cannot be cancelled from status " + status);
            }
            updateOrderStatus(c, orderId, OrderStatus.CANCELLED);
            updatePaymentStatus(c, orderId, PaymentStatus.REFUNDED);
            return new OrderCancelledEvent(orderId, reason == null ? "" : reason, Instant.now().toString());
        } catch (SQLException e) {
            throw new IllegalStateException("cancel-and-refund failed for " + orderId, e);
        }
    }

    private OrderStatus currentOrderStatus(Connection c, String orderId) throws SQLException {
        try (PreparedStatement select = c.prepareStatement("SELECT status FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? OrderStatus.valueOf(rs.getString(1)) : null;
            }
        }
    }

    private void updateOrderStatus(Connection c, String orderId, OrderStatus status) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE orders.orders SET status = ? WHERE id = ?")) {
            update.setString(1, status.name());
            update.setString(2, orderId);
            update.executeUpdate();
        }
    }

    private void updatePaymentStatus(Connection c, String orderId, PaymentStatus status) throws SQLException {
        try (PreparedStatement update =
                c.prepareStatement("UPDATE payments.payments SET status = ? WHERE order_id = ?")) {
            update.setString(1, status.name());
            update.setString(2, orderId);
            update.executeUpdate();
        }
    }
}
