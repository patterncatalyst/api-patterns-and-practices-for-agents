package com.patterncatalyst.agentapi.multiagentchoreography.shipping;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.multiagentchoreography.events.ShipmentScheduledEvent;
import dev.langchain4j.agent.tool.P;
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
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

/**
 * The one tool the woken shipping agent may call, whichever decision path chose the carrier
 * ({@link ShippingAdvisor} over Ollama, or {@link ShippingFixtureAdvisor} under MODEL=off). It
 * writes the durable {@code shipping.shipments} record, increments a counter, logs a line, and
 * publishes {@code apa.shipping.scheduled} (subject to {@link ShipmentScheduledDropGuard}, this
 * chapter's one negative witness) so the notification agent reacts on its own, with no method
 * call from this package into {@code notification}.
 */
@ApplicationScoped
public class ScheduleShipmentTool {

    private static final Logger LOG = Logger.getLogger(ScheduleShipmentTool.class);

    private final Counter invocations;
    private final DataSource dataSource;
    private final ProducerTemplate producer;

    @Inject
    public ScheduleShipmentTool(MeterRegistry registry, DataSource dataSource, ProducerTemplate producer) {
        this.invocations = Counter.builder("choreography.shipping.tool.invocations")
                .description("Number of times the scheduleShipment tool recorded and published a shipment")
                .register(registry);
        this.dataSource = dataSource;
        this.producer = producer;
    }

    @Tool("Schedule a shipment for an order with the chosen carrier (FASTLINE or AIRCO) and "
            + "publish ShipmentScheduled for the notification agent to react to")
    @Transactional
    public String scheduleShipment(
            @P("the order id, for example O-9001") String orderId,
            @P("the customer id") String customerId,
            @P("the carrier, FASTLINE or AIRCO") String carrier) {
        invocations.increment();
        Carrier chosen = Carrier.valueOf(carrier);
        String shipmentId = "SHP-" + orderId;
        String trackingNumber = "TRK-" + orderId;
        LOG.infof("tool invoked: scheduleShipment orderId=%s carrier=%s", orderId, chosen);
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO shipping.shipments (id, order_id, carrier, tracking_number, status) "
                            + "VALUES (?, ?, ?, ?, 'SCHEDULED') ON CONFLICT (id) DO NOTHING")) {
                insert.setString(1, shipmentId);
                insert.setString(2, orderId);
                insert.setString(3, chosen.name());
                insert.setString(4, trackingNumber);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("shipment record failed for " + orderId, e);
        }
        producer.sendBody("direct:publishShipmentScheduled",
                new ShipmentScheduledEvent(orderId, customerId, chosen, trackingNumber));
        return "shipment scheduled for " + orderId + " via " + chosen;
    }
}
