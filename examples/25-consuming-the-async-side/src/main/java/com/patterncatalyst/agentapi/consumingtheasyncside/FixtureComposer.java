package com.patterncatalyst.agentapi.consumingtheasyncside;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * The MODEL=off message path: composes the same kind of delivery notification prose
 * {@link NotificationComposerAgent} would, from a fixed template instead of calling Ollama, so
 * {@code mvn -B verify} and CI never need a model, mirroring
 * 17-event-triggered-agent-wakeup's {@code FixtureAdvisor} record/replay discipline. The message
 * text this produces is stored by {@link NotificationRecorder} but never asserted on by any test
 * or check: only the event-derived structured fields are, since a composed message (real model or
 * fixture) is free text, never a value this book's checks key on.
 */
@ApplicationScoped
public class FixtureComposer {

    private static final Logger LOG = Logger.getLogger(FixtureComposer.class);

    public String compose(ShipmentDelivered event) {
        String message = "Your order %s was delivered via %s, tracking number %s."
                .formatted(event.orderId(), event.carrier(), event.trackingNumber());
        LOG.infof("fixture composer: orderId=%s message=%s", event.orderId(), message);
        return message;
    }
}
