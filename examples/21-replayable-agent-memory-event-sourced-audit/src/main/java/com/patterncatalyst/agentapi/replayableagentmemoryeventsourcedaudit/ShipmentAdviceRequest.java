package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

/** A caller asking the agent's one tool for shipment advice: the inbound half of the audit pair. */
public record ShipmentAdviceRequest(String orderId, String identity) {
}
