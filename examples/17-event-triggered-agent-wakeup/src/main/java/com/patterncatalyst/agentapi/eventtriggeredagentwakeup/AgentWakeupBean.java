package com.patterncatalyst.agentapi.eventtriggeredagentwakeup;

import com.patterncatalyst.agentapi.Payment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin CDI bridge so the Camel Kafka consumer route can call the langchain4j AiService. The
 * generated {@link ShipmentAdvisor} proxy is request-scoped, but a Camel Kafka route runs on a
 * Vert.x worker thread with no request context active, so this method activates one for the
 * call, exactly as {@code AssistantBean} does in 00-stack-check for its HTTP-triggered call.
 *
 * <p>{@code agent.wakeup.model-source} picks the decision path: {@code ollama} (the default, and
 * what compose and the live verify.sh always use) lets the model decide and call the tool
 * itself; {@code fixture} (forced in the {@code %test} profile, MODEL=off) replays a recorded
 * decision instead, so Dev Services tests and CI never need a model.
 */
@ApplicationScoped
public class AgentWakeupBean {

    @Inject
    ShipmentAdvisor advisor;

    @Inject
    FixtureAdvisor fixtureAdvisor;

    @ConfigProperty(name = "agent.wakeup.model-source", defaultValue = "ollama")
    String modelSource;

    @ActivateRequestContext
    public void wake(Payment payment) {
        if ("fixture".equals(modelSource)) {
            fixtureAdvisor.advise(payment);
        } else {
            advisor.decide("Payment " + payment.id() + " for order " + payment.orderId()
                    + " amount " + payment.amount() + " was processed.");
        }
    }
}
