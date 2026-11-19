package com.patterncatalyst.agentapi.humanintheloopgate;

import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.PaymentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The gate itself. {@link #proposeAndHold} is the hold: it validates the order, writes one
 * {@code PENDING} row and returns, publishing nothing and touching neither {@code orders.orders}
 * nor {@code payments.payments}. {@link #applyApprove} and {@link #applyReject} are the resume:
 * each atomically claims a {@code PENDING} row for a terminal status before doing anything else,
 * so a redelivered decision (the same correlation id, consumed twice) can claim the row only
 * once; the second delivery finds it already terminal and performs no side effect, approve or
 * reject alike. Only {@link #applyApprove}'s claim ever reaches {@link #performCancelAndRefund}.
 */
@ApplicationScoped
public class CancelGateService {

    @Inject
    DataSource dataSource;

    /** Existence and lifecycle guard, run before the agent is ever called, so a trigger on an
     * unknown or already-terminal order fails the same way whichever model-source path is active. */
    public BigDecimal requireCancellable(String orderId) {
        try (Connection c = dataSource.getConnection()) {
            OrderStatus status = currentOrderStatus(c, orderId);
            if (status == null) {
                throw new OrderNotFoundException("order " + orderId + " does not exist");
            }
            if (!status.canMoveTo(OrderStatus.CANCELLED)) {
                throw new OrderNotCancellableException("order " + orderId + " cannot be cancelled from status " + status);
            }
            return currentPaymentAmount(c, orderId);
        } catch (SQLException e) {
            throw new IllegalStateException("cancellability check failed for " + orderId, e);
        }
    }

    @Transactional
    public GateOpened proposeAndHold(String orderId, String reasonCode, String reasonText) {
        BigDecimal amount = requireCancellable(orderId);
        try (Connection c = dataSource.getConnection()) {
            String correlationId = UUID.randomUUID().toString();
            insertPendingGate(c, correlationId, orderId, reasonText, reasonCode, amount);
            return new GateOpened(correlationId, orderId, reasonCode, amount.toPlainString(), reasonText);
        } catch (SQLException e) {
            throw new IllegalStateException("propose-and-hold failed for " + orderId, e);
        }
    }

    @Transactional
    public boolean applyApprove(String correlationId) {
        try (Connection c = dataSource.getConnection()) {
            String orderId = claimGate(c, correlationId, "APPROVED_EXECUTED");
            if (orderId == null) {
                return false; // unknown correlation id, or already decided: idempotent no-op
            }
            performCancelAndRefund(c, orderId);
            recordExecution(c, correlationId, orderId);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("apply-approve failed for " + correlationId, e);
        }
    }

    @Transactional
    public boolean applyReject(String correlationId) {
        try (Connection c = dataSource.getConnection()) {
            return claimGate(c, correlationId, "REJECTED_BY_USER") != null;
        } catch (SQLException e) {
            throw new IllegalStateException("apply-reject failed for " + correlationId, e);
        }
    }

    public CancelGateRecord latestGateFor(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT correlation_id, order_id, status, reason, reason_code, amount, "
                                + "proposed_at, decided_at FROM orders.cancel_gate WHERE order_id = ? "
                                + "ORDER BY proposed_at DESC LIMIT 1")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CancelGateRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6).toPlainString(), toInstantString(rs.getTimestamp(7)),
                        toInstantString(rs.getTimestamp(8)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("gate lookup failed for " + orderId, e);
        }
    }

    private void performCancelAndRefund(Connection c, String orderId) throws SQLException {
        updateOrderStatus(c, orderId, OrderStatus.CANCELLED);
        updatePaymentStatus(c, orderId, PaymentStatus.REFUNDED);
    }

    /**
     * Locks the row first ({@code FOR UPDATE}), then only a {@code PENDING} row is moved to
     * {@code newStatus}; an unknown correlation id, or one already in a terminal status, returns
     * {@code null} and leaves the row untouched. This is what makes a redelivered decision
     * idempotent: the first delivery's lock-then-check-then-update happens inside one
     * transaction, so a second delivery of the exact same message can never observe the row as
     * still {@code PENDING}.
     */
    private String claimGate(Connection c, String correlationId, String newStatus) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(
                "SELECT order_id, status FROM orders.cancel_gate WHERE correlation_id = ? FOR UPDATE")) {
            select.setString(1, correlationId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next() || !"PENDING".equals(rs.getString(2))) {
                    return null;
                }
                String orderId = rs.getString(1);
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE orders.cancel_gate SET status = ?, decided_at = now() WHERE correlation_id = ?")) {
                    update.setString(1, newStatus);
                    update.setString(2, correlationId);
                    update.executeUpdate();
                }
                return orderId;
            }
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

    private BigDecimal currentPaymentAmount(Connection c, String orderId) throws SQLException {
        try (PreparedStatement select =
                c.prepareStatement("SELECT amount FROM payments.payments WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new OrderNotFoundException("order " + orderId + " has no payment on record");
                }
                return rs.getBigDecimal(1);
            }
        }
    }

    private void insertPendingGate(Connection c, String correlationId, String orderId, String reason,
            String reasonCode, BigDecimal amount) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO orders.cancel_gate (correlation_id, order_id, reason, reason_code, amount, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING')")) {
            insert.setString(1, correlationId);
            insert.setString(2, orderId);
            insert.setString(3, reason == null ? "" : reason);
            insert.setString(4, reasonCode);
            insert.setBigDecimal(5, amount);
            insert.executeUpdate();
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

    private void recordExecution(Connection c, String correlationId, String orderId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO orders.cancel_execution_log (correlation_id, order_id) VALUES (?, ?) "
                        + "ON CONFLICT (correlation_id) DO NOTHING")) {
            insert.setString(1, correlationId);
            insert.setString(2, orderId);
            insert.executeUpdate();
        }
    }

    private static String toInstantString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
