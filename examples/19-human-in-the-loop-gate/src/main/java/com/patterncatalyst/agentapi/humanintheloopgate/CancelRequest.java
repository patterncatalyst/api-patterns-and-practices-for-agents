package com.patterncatalyst.agentapi.humanintheloopgate;

/** The trigger's request body: a free-text reason, the only input a caller provides. */
public record CancelRequest(String reason) {
}
