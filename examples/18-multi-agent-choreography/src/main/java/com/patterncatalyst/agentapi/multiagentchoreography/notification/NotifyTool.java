package com.patterncatalyst.agentapi.multiagentchoreography.notification;

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
import org.jboss.logging.Logger;

/**
 * The one tool the woken notification agent may call, whichever decision path called it
 * ({@link NotificationAdvisor} over Ollama, or {@link NotificationFixtureAdvisor} under
 * MODEL=off). It writes the durable {@code notifications.notifications} record and increments a
 * counter and logs a line, so the round trip is observable independent of the model's own
 * wording. This is the choreography's terminal effect: it publishes nothing further.
 */
@ApplicationScoped
public class NotifyTool {

    private static final Logger LOG = Logger.getLogger(NotifyTool.class);

    private final Counter invocations;
    private final DataSource dataSource;

    @Inject
    public NotifyTool(MeterRegistry registry, DataSource dataSource) {
        this.invocations = Counter.builder("choreography.notification.tool.invocations")
                .description("Number of times the sendNotification tool recorded a customer notification")
                .register(registry);
        this.dataSource = dataSource;
    }

    @Tool("Record that a customer was notified about an order event, as the choreography's "
            + "terminal step")
    @Transactional
    public String sendNotification(
            @P("the order id, for example O-9001") String orderId,
            @P("the customer id") String customerId,
            @P("the event type, for example ShipmentScheduled") String eventType) {
        invocations.increment();
        String id = "NOTIF-" + orderId;
        LOG.infof("tool invoked: sendNotification orderId=%s customerId=%s eventType=%s", orderId, customerId,
                eventType);
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO notifications.notifications (id, order_id, customer_id, event_type) "
                            + "VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING")) {
                insert.setString(1, id);
                insert.setString(2, orderId);
                insert.setString(3, customerId);
                insert.setString(4, eventType);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("notification record failed for " + orderId, e);
        }
        return "notification recorded for " + orderId;
    }
}
