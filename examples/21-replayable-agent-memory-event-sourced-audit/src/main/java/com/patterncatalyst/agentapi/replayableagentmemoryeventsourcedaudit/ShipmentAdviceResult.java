package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

/** The two audit record ids this one request appended, and the decision the outbound one carries. */
public record ShipmentAdviceResult(String inboundId, String outboundId, String carrier) {
}
