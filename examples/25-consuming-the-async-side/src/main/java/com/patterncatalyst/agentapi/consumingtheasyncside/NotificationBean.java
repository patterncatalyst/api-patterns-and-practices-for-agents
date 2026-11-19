package com.patterncatalyst.agentapi.consumingtheasyncside;

import com.patterncatalyst.agentapi.Notification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin CDI bridge so the Camel Kafka consumer route can call the langchain4j AiService. The
 * generated {@link NotificationComposerAgent} proxy is request-scoped, but a Camel Kafka route
 * runs on a Vert.x worker thread with no request context active, so this method activates one for
 * the call, exactly as {@code AgentWakeupBean} does in 17-event-triggered-agent-wakeup.
 *
 * <p>{@code agent.notification.model-source} picks the message path: {@code ollama} (the default,
 * and what compose and the live verify.sh always use) lets the real model compose the customer
 * notification text; {@code fixture} (forced in the {@code %test} profile, MODEL=off) composes a
 * deterministic templated message instead, so Dev Services tests and CI never need a model.
 *
 * <p>Either way, the structured fields of the durable {@link Notification} record, the order id,
 * the customer id and the event type, come straight from the consumed {@link ShipmentDelivered}
 * event, never from the model's reply: qwen3's prose is nondeterministic, so this book's checks
 * assert on the event-derived fields and leave the composed message unexamined past "not empty".
 */
@ApplicationScoped
public class NotificationBean {

    static final String SHIPMENT_DELIVERED_EVENT_TYPE = "SHIPMENT_DELIVERED";

    @Inject
    NotificationComposerAgent agent;

    @Inject
    FixtureComposer fixtureComposer;

    @Inject
    NotificationRecorder recorder;

    @ConfigProperty(name = "agent.notification.model-source", defaultValue = "ollama")
    String modelSource;

    @ActivateRequestContext
    public void notify(ShipmentDelivered event) {
        String message = "fixture".equals(modelSource)
                ? fixtureComposer.compose(event)
                : agent.compose("Order " + event.orderId() + " was delivered via " + event.carrier()
                        + ", tracking number " + event.trackingNumber() + ".");
        Notification notification = new Notification(
                UUID.randomUUID().toString(), event.orderId(), event.customerId(), SHIPMENT_DELIVERED_EVENT_TYPE);
        recorder.record(notification, message);
    }
}
