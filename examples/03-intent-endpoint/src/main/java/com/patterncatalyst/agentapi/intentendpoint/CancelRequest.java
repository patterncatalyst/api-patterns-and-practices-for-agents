package com.patterncatalyst.agentapi.intentendpoint;

/** The body of {@code POST /orders/{id}/cancel-and-refund}. */
public record CancelRequest(String reason) {
}
