package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The one tool {@link OrderStatusAdvisor} may call. It answers from {@link SeededOrderFacts}
 * only, so a call it makes is observable by its own log line independent of the model's
 * wording, the same reasoning {@code OrderStatusTool} in the stack-check example states: a
 * model's tool calling is nondeterministic, so this example's checks assert the tool's own
 * effects, never model prose.
 */
@ApplicationScoped
public class OrderStatusTool {

    private static final Logger LOG = Logger.getLogger(OrderStatusTool.class);

    @Inject
    SeededOrderFacts facts;

    @Tool("Look up the current status, carrier and tracking number for an order id")
    public String orderStatus(String orderId) {
        SeededOrderFacts.Fact fact = facts.find(orderId);
        if (fact == null) {
            LOG.infof("tool invoked: orderStatus orderId=%s result=unknown", orderId);
            return "No order found for id " + orderId;
        }
        String answer = fact.trackingNumber() == null
                ? "Order " + orderId + " is " + fact.status()
                : "Order " + orderId + " is " + fact.status() + ", carrier " + fact.carrier()
                        + ", tracking " + fact.trackingNumber();
        LOG.infof("tool invoked: orderStatus orderId=%s status=%s", orderId, fact.status());
        return answer;
    }
}
