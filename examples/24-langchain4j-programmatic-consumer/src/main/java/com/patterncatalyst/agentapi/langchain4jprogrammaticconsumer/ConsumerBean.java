package com.patterncatalyst.agentapi.langchain4jprogrammaticconsumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin CDI bridge so the Camel platform-http route can call the langchain4j AiService. The
 * generated {@link OrderStatusConsumerAgent} proxy is request-scoped, but a Camel platform-http
 * route runs on a Vert.x worker thread with no request context active, so this method activates
 * one for the call, exactly as {@code AssistantBean} does in 00-stack-check.
 *
 * <p>{@code agent.consumer.model-source} picks the decision path: {@code ollama} (the default, and
 * what compose and the live verify.sh always use) lets the model call the remote MCP tool itself;
 * {@code fixture} (forced in the {@code %test} profile, MODEL=off) calls the same remote tool
 * directly instead, so Dev Services tests and CI never need a model.
 */
@ApplicationScoped
public class ConsumerBean {

    @Inject
    OrderStatusConsumerAgent agent;

    @Inject
    FixtureConsumer fixtureConsumer;

    @ConfigProperty(name = "agent.consumer.model-source", defaultValue = "ollama")
    String modelSource;

    @ActivateRequestContext
    public void consume(String orderId) {
        if ("fixture".equals(modelSource)) {
            fixtureConsumer.consume(orderId);
        } else {
            agent.consume("Get the status of order " + orderId + " and record that you consulted it.");
        }
    }
}
