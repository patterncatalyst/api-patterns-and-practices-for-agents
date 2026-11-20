package com.patterncatalyst.agentapi.auditingaiusingcode;

/**
 * The one topic this chapter adds: an append-only log of outbound calls this code's own agent
 * makes. It carries no order-lifecycle fact, only a record of this code's own behaviour, so it is
 * kept local to this example, the same way 21-replayable-agent-memory-event-sourced-audit keeps
 * its own {@code apa.audit.agent-actions} topic local rather than adding it to the shared
 * domain-model's event catalog. The name is deliberately distinct from that chapter's topic: that
 * one audits both directions of what crosses an agent's own tool surface; this one audits only
 * the calls this code's own agent makes out to someone else's surface.
 */
final class AuditTopics {

    static final String OUTBOUND_CALLS = "apa.audit.outbound-calls";

    private AuditTopics() {
    }
}
