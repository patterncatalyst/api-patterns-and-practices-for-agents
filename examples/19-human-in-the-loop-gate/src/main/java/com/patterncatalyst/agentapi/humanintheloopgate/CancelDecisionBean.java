package com.patterncatalyst.agentapi.humanintheloopgate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Resolves one {@link CancelGateDecisionEvent} against the gate: {@code APPROVE} performs the
 * cancel-and-refund side effect exactly once; {@code REJECT} terminates the proposal with no
 * side effect, ever; any other value, or a decision that no longer matches a {@code PENDING}
 * row (a redelivered duplicate, or an unknown correlation id), is logged and ignored.
 */
@ApplicationScoped
public class CancelDecisionBean {

    private static final Logger LOG = Logger.getLogger(CancelDecisionBean.class);

    private final Counter approved;
    private final Counter rejected;
    private final Counter duplicatesIgnored;
    private final CancelGateService gateService;

    @Inject
    public CancelDecisionBean(MeterRegistry registry, CancelGateService gateService) {
        this.approved = Counter.builder("cancelgate.decision.outcomes")
                .description("Decisions resumed on the human-in-the-loop cancel gate, by outcome")
                .tag("outcome", "approved_executed")
                .register(registry);
        this.rejected = Counter.builder("cancelgate.decision.outcomes")
                .tag("outcome", "rejected_by_user")
                .register(registry);
        this.duplicatesIgnored = Counter.builder("cancelgate.decision.outcomes")
                .tag("outcome", "duplicate_ignored")
                .register(registry);
        this.gateService = gateService;
    }

    public void apply(CancelGateDecisionEvent event) {
        String decision = event.decision() == null ? "" : event.decision();
        switch (decision) {
            case "APPROVE" -> {
                if (gateService.applyApprove(event.correlationId())) {
                    approved.increment();
                    LOG.infof("decision applied: APPROVE correlationId=%s", event.correlationId());
                } else {
                    duplicatesIgnored.increment();
                    LOG.infof("decision ignored (not pending, likely a redelivered duplicate): "
                            + "correlationId=%s decision=APPROVE", event.correlationId());
                }
            }
            case "REJECT" -> {
                if (gateService.applyReject(event.correlationId())) {
                    rejected.increment();
                    LOG.infof("decision applied: REJECT correlationId=%s", event.correlationId());
                } else {
                    duplicatesIgnored.increment();
                    LOG.infof("decision ignored (not pending, likely a redelivered duplicate): "
                            + "correlationId=%s decision=REJECT", event.correlationId());
                }
            }
            default -> LOG.warnf("ignoring unknown decision value '%s' for correlationId=%s",
                    event.decision(), event.correlationId());
        }
    }
}
