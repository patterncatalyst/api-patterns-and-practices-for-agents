package com.patterncatalyst.agentapi.eventtriggeredagentwakeup;

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
 * The one tool the woken agent may call, whichever decision path chose the carrier
 * ({@link ShipmentAdvisor} over Ollama, or {@link FixtureAdvisor} under MODEL=off). It writes
 * the durable record (schema-qualified, like every other table in this book) and increments a
 * counter and logs a line, so the round trip is observable independent of the model's own
 * wording, the same reasoning {@code OrderStatusTool} in 00-stack-check states: qwen3's tool
 * calling is nondeterministic, so verify.sh asserts this side effect, never model prose.
 */
@ApplicationScoped
public class ShipmentPrepTool {

    private static final Logger LOG = Logger.getLogger(ShipmentPrepTool.class);

    private final Counter invocations;
    private final DataSource dataSource;

    @Inject
    public ShipmentPrepTool(MeterRegistry registry, DataSource dataSource) {
        this.invocations = Counter.builder("agentwakeup.tool.invocations")
                .description("Number of times the prepShipment tool recorded a shipment readiness decision")
                .register(registry);
        this.dataSource = dataSource;
    }

    @Tool("Record that a shipment is ready to prepare for an order, with the chosen carrier (FASTLINE or AIRCO)")
    @Transactional
    public String prepShipment(String orderId, String carrier) {
        invocations.increment();
        LOG.infof("tool invoked: prepShipment orderId=%s carrier=%s", orderId, carrier);
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO shipping.shipment_prep (order_id, carrier) VALUES (?, ?) "
                            + "ON CONFLICT (order_id) DO NOTHING")) {
                insert.setString(1, orderId);
                insert.setString(2, carrier);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("shipment prep record failed for " + orderId, e);
        }
        return "shipment prep recorded for " + orderId + " via " + carrier;
    }
}
