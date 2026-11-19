package com.patterncatalyst.agentapi.longrunningagentworkflow;

import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * The one tool the carrier-choice agent step may call, whichever decision path chose the carrier
 * ({@link CarrierAdvisor} over Ollama, or {@link FixtureCarrierAdvisor} under MODEL=off). It writes
 * the booking, completes the saga's own {@code orders.order_fulfillment} row, and increments a
 * counter and logs a line, so the effect is observable independent of the model's own wording:
 * qwen3's tool calling is nondeterministic, so verify.sh asserts this side effect, never model
 * prose, the same reasoning {@code ShipmentPrepTool} states in 17-event-triggered-agent-wakeup.
 */
@ApplicationScoped
public class CarrierBookingTool {

    private static final Logger LOG = Logger.getLogger(CarrierBookingTool.class);

    private final Counter bookings;
    private final DataSource dataSource;

    @Inject
    public CarrierBookingTool(MeterRegistry registry, DataSource dataSource) {
        this.bookings = Counter.builder("fulfillment.carrier.bookings")
                .description("Number of times the bookCarrier tool recorded a carrier booking")
                .register(registry);
        this.dataSource = dataSource;
    }

    @Tool("Book a carrier for a paid order's shipment (FASTLINE or AIRCO), completing its fulfillment")
    @Transactional
    public String bookCarrier(String orderId, String carrier) {
        bookings.increment();
        LOG.infof("tool invoked: bookCarrier orderId=%s carrier=%s", orderId, carrier);
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO shipping.shipment (order_id, carrier, status) VALUES (?, ?, 'BOOKED') "
                            + "ON CONFLICT (order_id) DO NOTHING")) {
                insert.setString(1, orderId);
                insert.setString(2, carrier);
                insert.executeUpdate();
            }
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE orders.order_fulfillment SET status = 'COMPLETED', carrier = ?, updated_at = now() "
                            + "WHERE order_id = ?")) {
                update.setString(1, carrier);
                update.setString(2, orderId);
                update.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("bookCarrier failed for " + orderId, e);
        }
        return "carrier booking recorded for " + orderId + " via " + carrier;
    }
}
