package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Picks the decision path {@code agent.traffic.model-source} names. {@code ollama} (the default,
 * and what compose and the live run always use) calls the real {@link OrderStatusAdvisor}
 * against a real model, which is what the framework instruments with its own GenAI span and
 * token-usage metric, and what wakes {@link TokenCostRecorder} through its {@code
 * ChatModelListener} callback. {@code fixture} (forced by the {@code %test} profile, MODEL=off)
 * routes to {@link FixtureTokenUsageAdvisor} instead, so Dev Services tests and CI never need a
 * model.
 */
@ApplicationScoped
public class AgentTrafficBean {

    @Inject
    OrderStatusAdvisor advisor;

    @Inject
    FixtureTokenUsageAdvisor fixtureAdvisor;

    @ConfigProperty(name = "agent.traffic.model-source", defaultValue = "ollama")
    String modelSource;

    public String ask(String orderId) {
        if ("fixture".equals(modelSource)) {
            return fixtureAdvisor.answer(orderId);
        }
        return advisor.answer("What is the status of order " + orderId + "?");
    }
}
