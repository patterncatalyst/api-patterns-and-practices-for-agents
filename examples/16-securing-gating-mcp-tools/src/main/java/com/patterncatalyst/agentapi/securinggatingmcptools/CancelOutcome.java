package com.patterncatalyst.agentapi.securinggatingmcptools;

/** The two terminal outcomes of {@code cancelOrder}'s elicitation confirm. REJECTED_BY_USER is not
 * an error: the caller asked permission, the user said no, and nothing was written. */
public enum CancelOutcome {
    CANCELLED, REJECTED_BY_USER
}
