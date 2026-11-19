package com.patterncatalyst.agentapi.langchain4jprogrammaticconsumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * The receipt for one remote {@code orderStatus} consultation. It is not a model-facing tool:
 * {@link ConsumerBean} (live path) reads the text the remote {@code orderStatus} tool actually
 * returned from {@code Result.toolExecutions()} and hands it here, and {@link FixtureConsumer}
 * (MODEL=off) hands the same remote result from its own direct MCP call. Either way this reads the
 * order status and shipment tracking number out of that JSON here, server side, so the durable row
 * carries values the remote tool actually returned, including the tracking number, which the model
 * has no way to supply except by reaching that tool. It also increments a Micrometer counter and
 * logs a line, so the round trip is observable independent of the model's own wording, the same
 * reasoning {@code ShipmentPrepTool} states in 17-event-triggered-agent-wakeup: qwen3's tool calling
 * is nondeterministic, so verify.sh asserts this side effect, never model prose.
 */
@ApplicationScoped
public class OrderStatusRecorder {

    private static final Logger LOG = Logger.getLogger(OrderStatusRecorder.class);

    private final Counter invocations;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public OrderStatusRecorder(MeterRegistry registry, DataSource dataSource, ObjectMapper objectMapper) {
        this.invocations = Counter.builder("consumer.mcp.tool.invocations")
                .description("Number of times the remote orderStatus MCP tool's result was recorded")
                .register(registry);
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String recordConsultation(String orderId, String orderStatusResult) {
        String orderStatus = "UNKNOWN";
        String trackingNumber = null;
        try {
            JsonNode node = objectMapper.readTree(orderStatusResult);
            if (node.hasNonNull("orderStatus")) {
                orderStatus = node.get("orderStatus").asText();
            }
            if (node.hasNonNull("trackingNumber")) {
                trackingNumber = node.get("trackingNumber").asText();
            }
        } catch (JsonProcessingException e) {
            LOG.warnf("orderStatus result for %s was not JSON, recording status UNKNOWN: %s",
                    orderId, orderStatusResult);
        }
        invocations.increment();
        LOG.infof("tool invoked: orderStatus orderId=%s status=%s tracking=%s", orderId, orderStatus, trackingNumber);
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO consumer.agent_order_status_consultation "
                                + "(order_id, order_status, tracking_number) VALUES (?, ?, ?) "
                                + "ON CONFLICT (order_id) DO UPDATE SET order_status = EXCLUDED.order_status, "
                                + "tracking_number = EXCLUDED.tracking_number, consulted_at = now()")) {
            insert.setString(1, orderId);
            insert.setString(2, orderStatus);
            if (trackingNumber == null) {
                insert.setNull(3, Types.VARCHAR);
            } else {
                insert.setString(3, trackingNumber);
            }
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("order status consultation record failed for " + orderId, e);
        }
        return "order status consultation recorded for " + orderId;
    }
}
