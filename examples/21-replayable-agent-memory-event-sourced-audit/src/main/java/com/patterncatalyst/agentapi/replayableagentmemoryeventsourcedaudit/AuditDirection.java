package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

/**
 * Which way an audited action crossed the agent's boundary: a caller invoking one of the
 * agent's own tools ({@link #INBOUND}), or the agent's langchain4j code calling a model
 * ({@link #OUTBOUND}). Both land in the same append-only log and the same projection.
 */
public enum AuditDirection {
    INBOUND, OUTBOUND
}
