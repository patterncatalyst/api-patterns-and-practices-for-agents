package com.patterncatalyst.agentapi.humanintheloopgate;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The one way a {@link PendingDecisionEvent} reaches {@code apa.orders.pending-decisions}:
 * {@code direct:cancel-gate-publish-pending}, called once by {@link CancelProposalTool} after
 * (never before) {@link CancelGateService#proposeAndHold} has committed the {@code PENDING} row,
 * the same DB-commits-before-Kafka-publishes sequencing ch06's {@code PlaceOrderRoute} and
 * ch11's commit path use. Keyed on the correlation id, so the decision that resumes this
 * proposal, published later on {@link CancelGateTopics#DECISIONS} with the same correlation id,
 * can be matched back to it.
 */
@ApplicationScoped
public class CancelGatePublishRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("direct:cancel-gate-publish-pending")
                .routeId("cancel-gate-publish-pending")
                .setHeader(KafkaConstants.KEY, simple("${body.correlationId}"))
                .marshal().json()
                .to("kafka:" + CancelGateTopics.PENDING_DECISIONS);
    }
}
