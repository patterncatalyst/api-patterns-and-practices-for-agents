package com.patterncatalyst.agentapi.humanintheloopgate;

import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

/**
 * The one tool the agent may call, whichever decision path classified the reason
 * ({@link CancelProposalAdvisor} over Ollama, or {@link FixtureCancelAdvisor} under MODEL=off).
 * It never cancels the order or refunds the payment itself: it opens a human-in-the-loop gate
 * ({@link CancelGateService#proposeAndHold}) and publishes the held proposal, the same reasoning
 * {@code ShipmentPrepTool} in chapter 17 states: observe the tool's own side effect, counter and
 * log line, never the model's prose, since qwen3's tool calling is nondeterministic.
 *
 * <p>The negative-witness bite for this chapter's "paused, no side effect before a decision"
 * check is a hand edit to the one call below: replacing {@code gateService.proposeAndHold(...)}
 * with a direct cancel-and-refund call skips the hold entirely. See the README and
 * {@code _plans/verification-log.md}.</p>
 */
@ApplicationScoped
public class CancelProposalTool {

    private static final Logger LOG = Logger.getLogger(CancelProposalTool.class);

    private final Counter invocations;
    private final CancelGateService gateService;
    private final ProducerTemplate producerTemplate;

    @Inject
    public CancelProposalTool(MeterRegistry registry, CancelGateService gateService, ProducerTemplate producerTemplate) {
        this.invocations = Counter.builder("cancelgate.tool.invocations")
                .description("Number of times the proposeCancelAndRefund tool opened a human-in-the-loop gate")
                .register(registry);
        this.gateService = gateService;
        this.producerTemplate = producerTemplate;
    }

    @Tool("Open a human-in-the-loop decision gate proposing to cancel an order and refund its payment, "
            + "classified by a reasonCode (DUPLICATE_ORDER, FRAUD_SUSPECTED or CUSTOMER_REQUEST); "
            + "never cancels the order or refunds the payment itself")
    public String proposeCancelAndRefund(String orderId, String reasonCode, String reasonText) {
        GateOpened opened = gateService.proposeAndHold(orderId, reasonCode, reasonText);

        PendingDecisionEvent event = new PendingDecisionEvent(opened.correlationId(), opened.orderId(),
                "CANCEL_AND_REFUND", opened.reasonCode(), opened.amount(), opened.reasonText(),
                Instant.now().toString());
        producerTemplate.sendBody("direct:cancel-gate-publish-pending", event);

        invocations.increment();
        LOG.infof("tool invoked: proposeCancelAndRefund orderId=%s correlationId=%s reasonCode=%s",
                orderId, opened.correlationId(), reasonCode);
        return "cancel-refund gate opened for " + orderId + " correlationId=" + opened.correlationId();
    }
}
