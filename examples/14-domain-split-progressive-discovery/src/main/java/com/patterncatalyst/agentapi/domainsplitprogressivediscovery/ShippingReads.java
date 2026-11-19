package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.ShipmentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The shipping server's only reads: both touch {@code shipping.shipments} alone, never
 * {@code orders.orders}. The shipping server has no opinion on whether an order id is valid, only
 * on whether it has a shipment row; that is a deliberate consequence of the split, not an
 * oversight.
 */
@ApplicationScoped
public class ShippingReads {

    private static final java.util.Map<ShipmentStatus, String> ETA_BY_STATUS = java.util.Map.of(
            ShipmentStatus.PENDING, "not yet scheduled",
            ShipmentStatus.SCHEDULED, "3 to 5 business days",
            ShipmentStatus.IN_TRANSIT, "1 to 2 business days",
            ShipmentStatus.DELIVERED, "already delivered");

    @Inject
    DataSource dataSource;

    public ShipmentTrackingInfo tracking(String orderId) {
        ShipmentRow row = shipmentRow(orderId);
        return new ShipmentTrackingInfo(orderId, row.id(), row.carrier(), row.trackingNumber(), row.status());
    }

    public ShipmentEta eta(String orderId) {
        ShipmentRow row = shipmentRow(orderId);
        return new ShipmentEta(orderId, row.status(), ETA_BY_STATUS.get(row.status()));
    }

    private ShipmentRow shipmentRow(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT id, carrier, tracking_number, status FROM shipping.shipments WHERE order_id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new ShipmentNotFoundException(orderId);
                }
                return new ShipmentRow(rs.getString("id"), Carrier.valueOf(rs.getString("carrier")),
                        rs.getString("tracking_number"), ShipmentStatus.valueOf(rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("shipment lookup failed for order " + orderId, e);
        }
    }

    private record ShipmentRow(String id, Carrier carrier, String trackingNumber, ShipmentStatus status) {
    }
}
