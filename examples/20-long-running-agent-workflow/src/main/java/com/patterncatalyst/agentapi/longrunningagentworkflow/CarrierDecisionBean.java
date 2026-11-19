package com.patterncatalyst.agentapi.longrunningagentworkflow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin CDI bridge so the Camel saga route can call the langchain4j AiService. The generated
 * {@link CarrierAdvisor} proxy is request-scoped, but a Camel route invoked via {@code direct:}
 * from the saga runs with no request context active, so this method activates one for the call,
 * the same bridge {@code AgentWakeupBean} uses in 17-event-triggered-agent-wakeup.
 *
 * <p>{@code agent.carrier.model-source} picks the decision path: {@code ollama} (the default, and
 * what compose and the live verify.sh always use) lets the model decide and call the tool itself;
 * {@code fixture} (forced in the {@code %test} profile, MODEL=off) replays a recorded decision
 * instead, so Dev Services tests and CI never need a model.
 */
@ApplicationScoped
public class CarrierDecisionBean {

    @Inject
    CarrierAdvisor advisor;

    @Inject
    FixtureCarrierAdvisor fixtureAdvisor;

    @Inject
    FulfillmentService service;

    @ConfigProperty(name = "agent.carrier.model-source", defaultValue = "ollama")
    String modelSource;

    @ActivateRequestContext
    public void bookCarrier(String orderId) {
        BigDecimal amount = service.amountFor(orderId);
        if ("fixture".equals(modelSource)) {
            fixtureAdvisor.advise(orderId, amount);
        } else {
            advisor.decide("Order " + orderId + " was paid, amount " + amount + ". Choose a carrier and book it.");
        }
    }
}
