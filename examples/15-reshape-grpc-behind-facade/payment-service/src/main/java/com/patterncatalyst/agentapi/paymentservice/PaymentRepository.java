package com.patterncatalyst.agentapi.paymentservice;

import com.patterncatalyst.agentapi.Payment;
import com.patterncatalyst.agentapi.PaymentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Owns the one table this service is responsible for, {@code payments.payments}, through the
 * shared domain {@link Payment} record: {@link #takePayment} upserts a {@code COMPLETED} payment
 * for an order, {@link #refundPayment} moves an existing {@code COMPLETED} payment to
 * {@code REFUNDED}. There is no Camel route here: both operations are a single synchronous write
 * answered over gRPC, not an event this service publishes.
 */
@ApplicationScoped
public class PaymentRepository {

    @Inject
    DataSource dataSource;

    public Payment takePayment(String orderId, BigDecimal amount) {
        String id = paymentId(orderId);
        try (Connection c = dataSource.getConnection();
                PreparedStatement upsert = c.prepareStatement(
                        "INSERT INTO payments.payments (id, order_id, amount, status) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (order_id) DO UPDATE SET amount = EXCLUDED.amount, "
                                + "status = EXCLUDED.status")) {
            upsert.setString(1, id);
            upsert.setString(2, orderId);
            upsert.setBigDecimal(3, amount);
            upsert.setString(4, PaymentStatus.COMPLETED.name());
            upsert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("takePayment failed for " + orderId, e);
        }
        return new Payment(id, orderId, amount, PaymentStatus.COMPLETED);
    }

    public Payment refundPayment(String orderId) {
        Payment existing = find(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
        if (existing.status() != PaymentStatus.COMPLETED) {
            throw new PaymentNotRefundableException(orderId, existing.status());
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement update = c.prepareStatement(
                        "UPDATE payments.payments SET status = ? WHERE order_id = ?")) {
            update.setString(1, PaymentStatus.REFUNDED.name());
            update.setString(2, orderId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("refundPayment failed for " + orderId, e);
        }
        return new Payment(existing.id(), orderId, existing.amount(), PaymentStatus.REFUNDED);
    }

    private Optional<Payment> find(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT id, amount, status FROM payments.payments WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Payment(rs.getString("id"), orderId, rs.getBigDecimal("amount"),
                        PaymentStatus.valueOf(rs.getString("status"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("payment lookup failed for " + orderId, e);
        }
    }

    /** O-9001 becomes PAY-9001: readable, and stable across repeated takePayment calls for the
     * same order (the upsert above keys on order_id). */
    private static String paymentId(String orderId) {
        return orderId.startsWith("O-") ? "PAY-" + orderId.substring(2) : "PAY-" + orderId;
    }
}
