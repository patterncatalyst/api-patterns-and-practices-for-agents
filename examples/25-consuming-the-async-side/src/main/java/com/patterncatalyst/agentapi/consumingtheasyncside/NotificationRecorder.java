package com.patterncatalyst.agentapi.consumingtheasyncside;

import com.patterncatalyst.agentapi.Notification;
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
 * The deterministic write the woken agent's composed message ends at, whichever decision path
 * produced it ({@link NotificationComposerAgent} over Ollama, or {@link FixtureComposer} under
 * MODEL=off). The structured columns, order id, event type and customer id, come from the caller's
 * {@link Notification}, built in {@link NotificationBean} directly from the consumed
 * {@link ShipmentDelivered} event, never from the model. {@code ON CONFLICT (order_id, event_type)
 * DO NOTHING} is the idempotency guard: the same event redelivered (same order id, same event
 * type) writes at most once, the row from whichever delivery got there first. It also increments a
 * counter and logs a line, so the round trip is observable independent of the model's own wording,
 * the same reasoning {@code ShipmentPrepTool} states in 17-event-triggered-agent-wakeup: qwen3's
 * composed prose is nondeterministic, so verify.sh asserts this side effect and the event-derived
 * columns, never the message text.
 */
@ApplicationScoped
public class NotificationRecorder {

    private static final Logger LOG = Logger.getLogger(NotificationRecorder.class);

    private final Counter recordings;
    private final DataSource dataSource;

    @Inject
    public NotificationRecorder(MeterRegistry registry, DataSource dataSource) {
        this.recordings = Counter.builder("consumingasyncside.notification.recorded")
                .description("Number of times a shipment-delivered customer notification was recorded")
                .register(registry);
        this.dataSource = dataSource;
    }

    @Transactional
    public void record(Notification notification, String message) {
        recordings.increment();
        LOG.infof("notification recorded: orderId=%s eventType=%s customerId=%s",
                notification.orderId(), notification.eventType(), notification.customerId());
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO notify.notifications (id, order_id, event_type, customer_id, message) "
                                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (order_id, event_type) DO NOTHING")) {
            insert.setString(1, notification.id());
            insert.setString(2, notification.orderId());
            insert.setString(3, notification.eventType());
            insert.setString(4, notification.customerId());
            insert.setString(5, message);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("notification record failed for " + notification.orderId(), e);
        }
    }
}
