package com.patterncatalyst.agentapi.humanintheloopgate;

/**
 * What {@link CancelGateService#proposeAndHold} returns: everything {@link CancelProposalTool}
 * needs to publish the pending-decision event, without a second database read.
 */
record GateOpened(String correlationId, String orderId, String reasonCode, String amount, String reasonText) {
}
