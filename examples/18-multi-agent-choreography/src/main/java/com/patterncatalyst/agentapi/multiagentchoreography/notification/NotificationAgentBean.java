package com.patterncatalyst.agentapi.multiagentchoreography.notification;

import com.patterncatalyst.agentapi.multiagentchoreography.events.ShipmentScheduledEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin CDI bridge so the Camel Kafka consumer route can call the langchain4j AiService. The
 * generated {@link NotificationAdvisor} proxy is request-scoped, but a Camel Kafka route runs on
 * a Vert.x worker thread with no request context active, so this method activates one for the
 * call, exactly as {@code AgentWakeupBean} does in 17-event-triggered-agent-wakeup.
 *
 * <p>{@code notification.agent.model-source} picks the decision path: {@code ollama} (the
 * default, and what compose and the live verify.sh always use) lets the model decide and call
 * the tool itself; {@code fixture} (forced in the {@code %test} profile, MODEL=off) calls the
 * tool directly, so Dev Services tests and CI never need a model.
 */
@ApplicationScoped
public class NotificationAgentBean {

    @Inject
    NotificationAdvisor advisor;

    @Inject
    NotificationFixtureAdvisor fixtureAdvisor;

    @ConfigProperty(name = "notification.agent.model-source", defaultValue = "ollama")
    String modelSource;

    @ActivateRequestContext
    public void wake(ShipmentScheduledEvent event) {
        if ("fixture".equals(modelSource)) {
            fixtureAdvisor.notify(event);
        } else {
            advisor.decide("Shipment for order " + event.orderId() + " customer " + event.customerId()
                    + " was scheduled with " + event.carrier() + ", tracking " + event.trackingNumber() + ".");
        }
    }
}
