package com.patterncatalyst.agentapi.longrunningagentworkflow;

import com.patterncatalyst.agentapi.Order;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The three compensable steps of the order-fulfillment saga (reserve stock, take payment, book a
 * carrier) and their compensations (release stock, refund payment, cancel the booking), plus the
 * guarded start-of-saga row in {@code orders.order_fulfillment}. Raw JDBC against the
 * {@link DataSource}, the same style as {@code ShipmentPrepTool} in
 * 17-event-triggered-agent-wakeup: no Panache entity for a table this narrow.
 *
 * <p>Every compensation is written as a guarded, single UPDATE that only takes effect from the
 * exact prior state it undoes (for example {@code WHERE status = 'STOCK_RESERVED'}), so calling it
 * twice (Camel compensating the same enlisted step more than once, or a saga retried after a
 * partial failure) changes nothing the second time: idempotent by construction, the same idiom the
 * event-driven-patterns-with-quarkus-and-camel sibling's chapter 22 saga uses for its stock
 * release.
 *
 * <p>{@code fulfillment.compensation.enabled} is demo code: {@code false} (the negative witness in
 * verify.sh) turns every compensation method into a no-op that only logs, so a step's effect (the
 * decremented stock, the taken payment, the booked carrier) survives a failure further down the
 * saga instead of being undone.
 */
@ApplicationScoped
public class FulfillmentService {

    private static final Logger LOG = Logger.getLogger(FulfillmentService.class);

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "fulfillment.compensation.enabled", defaultValue = "true")
    boolean compensationEnabled;

    @ConfigProperty(name = "fulfillment.payment.decline-above")
    BigDecimal declineAbove;

    @Transactional
    public void reserveStock(String orderId, Order order) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO orders.order_fulfillment (order_id, item_sku, quantity, amount, status) "
                            + "VALUES (?, ?, ?, ?, 'STARTED') ON CONFLICT (order_id) DO NOTHING")) {
                insert.setString(1, orderId);
                insert.setString(2, order.itemSku());
                insert.setInt(3, order.quantity());
                insert.setBigDecimal(4, order.amount());
                insert.executeUpdate();
            }
            int reserved;
            try (PreparedStatement decrement = c.prepareStatement(
                    "UPDATE inventory.inventory_item SET quantity_on_hand = quantity_on_hand - ? "
                            + "WHERE sku = ? AND quantity_on_hand >= ?")) {
                decrement.setInt(1, order.quantity());
                decrement.setString(2, order.itemSku());
                decrement.setInt(3, order.quantity());
                reserved = decrement.executeUpdate();
            }
            if (reserved == 0) {
                throw new InsufficientStockException(orderId, order.itemSku());
            }
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE orders.order_fulfillment SET status = 'STOCK_RESERVED', updated_at = now() "
                            + "WHERE order_id = ?")) {
                update.setString(1, orderId);
                update.executeUpdate();
            }
            LOG.infof("step reserveStock: orderId=%s sku=%s quantity=%d", orderId, order.itemSku(), order.quantity());
        } catch (SQLException e) {
            throw new IllegalStateException("reserveStock failed for " + orderId, e);
        }
    }

    @Transactional
    public void releaseStock(String orderId) {
        if (!compensationEnabled) {
            LOG.infof("compensation disabled: releaseStock skipped for orderId=%s (demo code)", orderId);
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            String sku;
            int quantity;
            try (PreparedStatement guard = c.prepareStatement(
                    "UPDATE orders.order_fulfillment SET status = 'COMPENSATED', updated_at = now() "
                            + "WHERE order_id = ? AND status = 'STOCK_RESERVED' RETURNING item_sku, quantity")) {
                guard.setString(1, orderId);
                try (ResultSet rs = guard.executeQuery()) {
                    if (!rs.next()) {
                        LOG.infof("compensation releaseStock: orderId=%s already compensated or never reserved, no-op",
                                orderId);
                        return;
                    }
                    sku = rs.getString(1);
                    quantity = rs.getInt(2);
                }
            }
            try (PreparedStatement restore = c.prepareStatement(
                    "UPDATE inventory.inventory_item SET quantity_on_hand = quantity_on_hand + ? WHERE sku = ?")) {
                restore.setInt(1, quantity);
                restore.setString(2, sku);
                restore.executeUpdate();
            }
            LOG.infof("compensation releaseStock: orderId=%s sku=%s quantity=%d restored", orderId, sku, quantity);
        } catch (SQLException e) {
            throw new IllegalStateException("releaseStock failed for " + orderId, e);
        }
    }

    @Transactional
    public void takePayment(String orderId) {
        BigDecimal amount = amountFor(orderId);
        if (amount.compareTo(declineAbove) >= 0) {
            throw new PaymentDeclinedException(orderId, "amount " + amount + " is at or above the decline limit " + declineAbove);
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO payments.payment (order_id, amount, status) VALUES (?, ?, 'COMPLETED') "
                            + "ON CONFLICT (order_id) DO NOTHING")) {
                insert.setString(1, orderId);
                insert.setBigDecimal(2, amount);
                insert.executeUpdate();
            }
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE orders.order_fulfillment SET status = 'PAID', updated_at = now() WHERE order_id = ?")) {
                update.setString(1, orderId);
                update.executeUpdate();
            }
            LOG.infof("step takePayment: orderId=%s amount=%s", orderId, amount);
        } catch (SQLException e) {
            throw new IllegalStateException("takePayment failed for " + orderId, e);
        }
    }

    @Transactional
    public void refundPayment(String orderId) {
        if (!compensationEnabled) {
            LOG.infof("compensation disabled: refundPayment skipped for orderId=%s (demo code)", orderId);
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement refund = c.prepareStatement(
                    "UPDATE payments.payment SET status = 'REFUNDED', updated_at = now() "
                            + "WHERE order_id = ? AND status = 'COMPLETED'")) {
                refund.setString(1, orderId);
                int rows = refund.executeUpdate();
                LOG.infof("compensation refundPayment: orderId=%s rows=%d", orderId, rows);
            }
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE orders.order_fulfillment SET status = 'COMPENSATED', updated_at = now() "
                            + "WHERE order_id = ? AND status = 'PAID'")) {
                update.setString(1, orderId);
                update.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("refundPayment failed for " + orderId, e);
        }
    }

    @Transactional
    public void cancelBooking(String orderId) {
        if (!compensationEnabled) {
            LOG.infof("compensation disabled: cancelBooking skipped for orderId=%s (demo code)", orderId);
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement cancel = c.prepareStatement(
                    "UPDATE shipping.shipment SET status = 'CANCELLED', updated_at = now() "
                            + "WHERE order_id = ? AND status = 'BOOKED'")) {
                cancel.setString(1, orderId);
                int rows = cancel.executeUpdate();
                LOG.infof("compensation cancelBooking: orderId=%s rows=%d", orderId, rows);
            }
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE orders.order_fulfillment SET status = 'COMPENSATED', updated_at = now() "
                            + "WHERE order_id = ? AND status = 'COMPLETED'")) {
                update.setString(1, orderId);
                update.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cancelBooking failed for " + orderId, e);
        }
    }

    public BigDecimal amountFor(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT amount FROM orders.order_fulfillment WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no order_fulfillment row for " + orderId);
                }
                return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("amountFor failed for " + orderId, e);
        }
    }

    public String statusOf(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT status FROM orders.order_fulfillment WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("statusOf failed for " + orderId, e);
        }
    }

    public Integer stockOf(String sku) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT quantity_on_hand FROM inventory.inventory_item WHERE sku = ?")) {
            select.setString(1, sku);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("stockOf failed for " + sku, e);
        }
    }

    public String paymentStatusOf(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT status FROM payments.payment WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("paymentStatusOf failed for " + orderId, e);
        }
    }

    public String shipmentStatusOf(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT status FROM shipping.shipment WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("shipmentStatusOf failed for " + orderId, e);
        }
    }
}
