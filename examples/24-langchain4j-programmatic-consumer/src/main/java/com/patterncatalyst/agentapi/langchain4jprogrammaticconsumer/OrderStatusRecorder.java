package com.patterncatalyst.agentapi.langchain4jprogrammaticconsumer;

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
 * The one local tool on {@link OrderStatusConsumerAgent}, alongside the remote {@code orderStatus}
 * tool registered by {@code @McpToolBox("apa-orders")}. The remote tool does the work (reads
 * ch13's composed order status over MCP); this tool is the receipt: it writes the durable record,
 * increments a Micrometer counter and logs a line, so the round trip is observable independent of
 * the model's own wording, the same reasoning {@code ShipmentPrepTool} states in
 * 17-event-triggered-agent-wakeup: qwen3's tool calling is nondeterministic, so verify.sh asserts
 * this side effect, never model prose. {@link FixtureConsumer} calls this same method directly
 * under {@code agent.consumer.model-source=fixture} (MODEL=off), so the durable record, the
 * counter and the log line are identical whichever path produced them.
 */
@ApplicationScoped
public class OrderStatusRecorder {

    private static final Logger LOG = Logger.getLogger(OrderStatusRecorder.class);

    private final Counter invocations;
    private final DataSource dataSource;

    @Inject
    public OrderStatusRecorder(MeterRegistry registry, DataSource dataSource) {
        this.invocations = Counter.builder("consumer.mcp.tool.invocations")
                .description("Number of times the remote orderStatus MCP tool's result was recorded")
                .register(registry);
        this.dataSource = dataSource;
    }

    @Tool("Record that this order's status was consulted over the remote orderStatus MCP tool, "
            + "with the order status value learned")
    @Transactional
    public String recordConsultation(String orderId, String orderStatus) {
        invocations.increment();
        LOG.infof("tool invoked: orderStatus orderId=%s status=%s", orderId, orderStatus);
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO consumer.agent_order_status_consultation (order_id, order_status) "
                                + "VALUES (?, ?) ON CONFLICT (order_id) "
                                + "DO UPDATE SET order_status = EXCLUDED.order_status, consulted_at = now()")) {
            insert.setString(1, orderId);
            insert.setString(2, orderStatus);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("order status consultation record failed for " + orderId, e);
        }
        return "order status consultation recorded for " + orderId;
    }
}
