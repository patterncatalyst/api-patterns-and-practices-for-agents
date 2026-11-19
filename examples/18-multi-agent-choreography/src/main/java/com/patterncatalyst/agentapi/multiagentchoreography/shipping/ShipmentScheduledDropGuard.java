package com.patterncatalyst.agentapi.multiagentchoreography.shipping;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Demo code: the negative witness this chapter's live verify.sh (run by the orchestrator, not
 * this script) exercises. {@code choreography.drop-shipment-scheduled=true}
 * ({@code CHOREOGRAPHY_DROP_SHIPMENT_SCHEDULED} in compose) makes {@link
 * ShippingEventRoutes}'s Camel {@code filter()} silently drop every {@code ShipmentScheduled}
 * event instead of publishing it: the link between shipping and notification is cut without
 * touching either agent's Kafka consumer, so the chain stalls exactly at that hop and the
 * notification agent never wakes. {@code false} (the default) is the whole point of this
 * chapter: every hop actually reaches the next agent.
 */
@ApplicationScoped
public class ShipmentScheduledDropGuard {

    @ConfigProperty(name = "choreography.drop-shipment-scheduled", defaultValue = "false")
    boolean dropEnabled;

    public boolean allowPublish() {
        return !dropEnabled;
    }
}
