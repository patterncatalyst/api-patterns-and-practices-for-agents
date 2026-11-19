package com.patterncatalyst.agentapi.proposepreviewcommit;

import com.patterncatalyst.agentapi.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

/**
 * The read-only preview path: computes exactly what {@link CancelRefundSaga#cancelAndRefund}
 * would do, by reading current state, performing no write and reaching no Camel route, so a
 * dry run can never publish an event. Runs the same existence and lifecycle guard the commit
 * path runs, so a preview on an order that cannot legally be cancelled reports the same 404 or
 * 409 a commit attempt on it would.
 */
@ApplicationScoped
public class CancelReviewService {

    @Inject
    DataSource dataSource;

    public ReviewPayload review(String orderId) {
        try (Connection c = dataSource.getConnection()) {
            OrderStatus status = currentOrderStatus(c, orderId);
            if (status == null) {
                throw new OrderNotFoundException("order " + orderId + " does not exist");
            }
            if (status != OrderStatus.PLACED && status != OrderStatus.PAID) {
                throw new OrderNotCancellableException(
                        "order " + orderId + " cannot be cancelled from status " + status);
            }
            BigDecimal refundAmount = paymentAmount(c, orderId);
            String effectSummary = "cancel order " + orderId + " and refund its payment of " + refundAmount;
            return new ReviewPayload(orderId, effectSummary, status.name(), OrderStatus.CANCELLED.name(),
                    refundAmount, List.of("OrderCancelled"), List.of(), false);
        } catch (SQLException e) {
            throw new IllegalStateException("cancel review failed for " + orderId, e);
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

    private BigDecimal paymentAmount(Connection c, String orderId) throws SQLException {
        try (PreparedStatement select =
                c.prepareStatement("SELECT amount FROM payments.payments WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }
}
