package com.patterncatalyst.agentapi.multiagentchoreography.shipping;

import com.patterncatalyst.agentapi.Order;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin CDI bridge so the Camel Kafka consumer route can call the langchain4j AiService. The
 * generated {@link ShippingAdvisor} proxy is request-scoped, but a Camel Kafka route runs on a
 * Vert.x worker thread with no request context active, so this method activates one for the
 * call, exactly as {@code AgentWakeupBean} does in 17-event-triggered-agent-wakeup.
 *
 * <p>{@code shipping.agent.model-source} picks the decision path: {@code ollama} (the default,
 * and what compose and the live verify.sh always use) lets the model decide the carrier and call
 * the tool itself; {@code fixture} (forced in the {@code %test} profile, MODEL=off) replays a
 * recorded decision instead, so Dev Services tests and CI never need a model.
 */
@ApplicationScoped
public class ShippingAgentBean {

    @Inject
    ShippingAdvisor advisor;

    @Inject
    ShippingFixtureAdvisor fixtureAdvisor;

    @ConfigProperty(name = "shipping.agent.model-source", defaultValue = "ollama")
    String modelSource;

    @ActivateRequestContext
    public void wake(Order order) {
        if ("fixture".equals(modelSource)) {
            fixtureAdvisor.advise(order);
        } else {
            advisor.decide("Order " + order.id() + " for customer " + order.customerId()
                    + " amount " + order.amount() + " was placed.");
        }
    }
}
