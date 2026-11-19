package com.patterncatalyst.agentapi.agentbehavioureval;

/**
 * A business rule violation raised by {@link AgentOrderService}. {@link CancelAndRefundTools}
 * turns it into an instructive {@code "error: ..."} string for the LIVE path's model to read,
 * never a stack trace.
 */
public class AgentOrderException extends RuntimeException {

    public AgentOrderException(String message) {
        super(message);
    }
}
