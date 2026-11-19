package com.patterncatalyst.agentapi.multiagentchoreography.order;

import com.patterncatalyst.agentapi.Order;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin CDI bridge so the HTTP intake route can call the langchain4j AiService. The generated
 * {@link OrderAdvisor} proxy is request-scoped, but a Camel platform-http route runs on a
 * Vert.x worker thread with no request context active, so this method activates one for the
 * call, exactly as {@code AssistantBean} does in 00-stack-check.
 *
 * <p>{@code order.agent.model-source} picks the decision path: {@code ollama} (the default, and
 * what compose and the live verify.sh always use) lets the model decide and call the tool
 * itself; {@code fixture} (forced in the {@code %test} profile, MODEL=off) calls the tool
 * directly, so Dev Services tests and CI never need a model.
 */
@ApplicationScoped
public class OrderAgentBean {

    @Inject
    OrderAdvisor advisor;

    @Inject
    OrderFixtureAdvisor fixtureAdvisor;

    @ConfigProperty(name = "order.agent.model-source", defaultValue = "ollama")
    String modelSource;

    @ActivateRequestContext
    public void place(Order order) {
        if ("fixture".equals(modelSource)) {
            fixtureAdvisor.place(order);
        } else {
            advisor.decide("New order id " + order.id() + " customerId " + order.customerId()
                    + " itemSku " + order.itemSku() + " quantity " + order.quantity()
                    + " amount " + order.amount() + " was placed.");
        }
    }
}
