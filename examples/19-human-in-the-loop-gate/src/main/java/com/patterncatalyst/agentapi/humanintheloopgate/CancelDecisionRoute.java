package com.patterncatalyst.agentapi.humanintheloopgate;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

/**
 * The resume: a Kafka consumer on {@code apa.orders.decisions} that hands every
 * {@link CancelGateDecisionEvent} to {@link CancelDecisionBean}, which resolves it against
 * {@link CancelGateService#applyApprove} or {@link CancelGateService#applyReject}. This is the
 * one edge where the gate's hold actually ends: the cancel-and-refund side effect never runs on
 * the trigger thread, only here, and only once per correlation id.
 */
@ApplicationScoped
public class CancelDecisionRoute extends RouteBuilder {

    @Inject
    CancelDecisionBean decisionBean;

    @Override
    public void configure() {
        from("kafka:" + CancelGateTopics.DECISIONS + "?groupId={{cancelgate.decisions.group-id}}")
                .routeId("cancel-gate-decision-consumer")
                .unmarshal().json(CancelGateDecisionEvent.class)
                .process(exchange -> decisionBean.apply(exchange.getIn().getBody(CancelGateDecisionEvent.class)));
    }
}
