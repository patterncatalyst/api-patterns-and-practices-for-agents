package com.patterncatalyst.agentapi.trafficandresourceguardrails;

/** Returned once a payload clears both guardrails. */
public record IngestAck(String orderId, String status) {

    public static IngestAck accepted(String orderId) {
        return new IngestAck(orderId, "ACCEPTED");
    }
}
