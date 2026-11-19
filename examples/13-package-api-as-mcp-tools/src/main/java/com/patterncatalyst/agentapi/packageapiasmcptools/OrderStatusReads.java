package com.patterncatalyst.agentapi.packageapiasmcptools;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.PaymentStatus;
import com.patterncatalyst.agentapi.ShipmentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The reads this chapter composes: one order row ({@code orders.orders}), one payment row
 * ({@code payments.payments}) and one shipment row ({@code shipping.shipments}), each a separate
 * read against a separate schema. {@link #paymentStatus} and {@link #shipmentTracking} stay
 * independent, single-purpose reads; {@link #orderStatus} is the coarse read: it calls both and
 * reshapes the three results into one response, rather than leaving a caller to stitch three
 * separate tool calls together itself. This example has nothing to write, so there is no Camel
 * route: a read has no event to publish.
 */
@ApplicationScoped
public class OrderStatusReads {

    @Inject
    DataSource dataSource;

    public ComposedOrderStatus orderStatus(String orderId) {
        OrderRow order = orderRow(orderId);
        PaymentStatusView payment = paymentStatus(orderId);
        ShipmentTrackingView shipment = shipmentTracking(orderId);
        return new ComposedOrderStatus(order.id(), order.customerId(), order.itemSku(), order.quantity(),
                order.amount(), order.status(), payment.status(), payment.amount(), shipment.carrier(),
                shipment.trackingNumber(), shipment.status());
    }

    public PaymentStatusView paymentStatus(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT id, amount, status FROM payments.payments WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new OrderNotFoundException(orderId);
                }
                return new PaymentStatusView(orderId, rs.getString("id"), rs.getBigDecimal("amount"),
                        PaymentStatus.valueOf(rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("payment status lookup failed for " + orderId, e);
        }
    }

    public ShipmentTrackingView shipmentTracking(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT id, carrier, tracking_number, status FROM shipping.shipments WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new OrderNotFoundException(orderId);
                }
                return new ShipmentTrackingView(orderId, rs.getString("id"), Carrier.valueOf(rs.getString("carrier")),
                        rs.getString("tracking_number"), ShipmentStatus.valueOf(rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("shipment tracking lookup failed for " + orderId, e);
        }
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
                        rs.getInt("quantity"), rs.getBigDecimal("amount"),
                        OrderStatus.valueOf(rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("order lookup failed for " + orderId, e);
        }
    }

    private record OrderRow(String id, String customerId, String itemSku, int quantity, BigDecimal amount,
            OrderStatus status) {
    }
}
