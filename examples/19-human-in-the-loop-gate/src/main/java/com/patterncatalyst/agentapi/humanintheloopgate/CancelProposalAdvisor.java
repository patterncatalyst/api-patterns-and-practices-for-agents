package com.patterncatalyst.agentapi.humanintheloopgate;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A local langchain4j AiService backed directly by quarkus-langchain4j-ollama (not the Camel
 * langchain4j-agent component), called from {@link CancelRequestResource} once the trigger's
 * existence and cancellability checks pass. It never cancels an order or refunds a payment
 * itself: its only job is to classify the stated reason and call the one tool that opens the
 * gate.
 */
@RegisterAiService(tools = CancelProposalTool.class)
@ApplicationScoped
public interface CancelProposalAdvisor {

    @SystemMessage("""
            You are a cancellation-and-refund gate agent. You never cancel an order or refund a
            payment yourself. Call the proposeCancelAndRefund tool exactly once, passing the
            order id, the reason text verbatim, and a reasonCode classified from that reason:
            DUPLICATE_ORDER if it mentions a duplicate order, FRAUD_SUSPECTED if it mentions
            fraud, otherwise CUSTOMER_REQUEST.
            """)
    String propose(String message);
}
