package com.patterncatalyst.agentapi.multiagentchoreography.notification;

import com.patterncatalyst.agentapi.multiagentchoreography.events.ShipmentScheduledEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The MODEL=off decision path for the notification agent: notifying has no branch to decide
 * (unlike the shipping agent's carrier choice), so there is no recorded-decision fixture file to
 * replay, only the one deterministic action calling the tool directly. {@code mvn -B verify} and
 * CI never need a model. Either path (this one, or {@link NotificationAdvisor} over Ollama)
 * reaches the same {@link NotifyTool#sendNotification(String, String, String)}, so the durable
 * record, the counter and the log line this book's checks assert on are identical whichever path
 * produced them.
 */
@ApplicationScoped
public class NotificationFixtureAdvisor {

    @Inject
    NotifyTool tool;

    public void notify(ShipmentScheduledEvent event) {
        tool.sendNotification(event.orderId(), event.customerId(), "ShipmentScheduled");
    }
}
