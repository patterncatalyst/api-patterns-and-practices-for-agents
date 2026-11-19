package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

/**
 * The one topic this chapter adds: an append-only audit log of agent actions, both directions
 * (an inbound tool call the agent consumed, and an outbound model call its langchain4j code
 * made). It is not one of the book's nine event-catalog topics in {@code domain-model}'s
 * {@code Topics} (it carries no order lifecycle fact, only a record of an agent's own
 * behaviour), so it is kept local to this example, the same way every example keeps its own
 * event payload records local rather than adding them to the shared module.
 */
final class AuditTopics {

    static final String AGENT_ACTIONS = "apa.audit.agent-actions";

    private AuditTopics() {
    }
}
