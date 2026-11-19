package com.patterncatalyst.agentapi.agentbehavioureval;

import com.patterncatalyst.agentapi.Payment;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The one tool the LIVE path's {@link AgentBehaviourAssistant} may call: cancel a shipping order
 * and refund the customer in a single step, the same single-intent shape 01-why-agent-apis's good
 * contract uses. {@link BehaviourEvaluator} never reads this method's return value directly: what
 * it scores is {@link LiveRunner}'s captured {@code dev.langchain4j.service.Result#toolExecutions()},
 * read the same way whether the call came from here live or from a committed fixture under
 * {@code src/main/resources/fixtures/}.
 */
@ApplicationScoped
public class CancelAndRefundTools {

    private static final Logger LOG = Logger.getLogger(CancelAndRefundTools.class);

    @Inject
    AgentOrderService service;

    @Tool("""
            Cancel a shipping order and refund the customer in a single step. Use this whenever
            asked to cancel an order and refund the customer. If it cannot complete, it returns a
            message stating exactly what is wrong.
            """)
    public String cancelAndRefund(
            @P("the order id, for example O-28001") String orderId,
            @P("a short free-text reason for the cancellation") String reason) {
        LOG.infof("live tool call: cancelAndRefund orderId=%s reason=%s", orderId, reason);
        try {
            Payment refund = service.cancelAndRefund(orderId, reason);
            return "Order " + orderId + " is cancelled and " + refund.amount()
                    + " has been refunded to the original payment method.";
        } catch (AgentOrderException e) {
            LOG.infof("live tool arg error: cancelAndRefund orderId=%s message=%s", orderId, e.getMessage());
            return "error: " + e.getMessage();
        }
    }
}
