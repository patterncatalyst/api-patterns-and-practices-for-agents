package com.patterncatalyst.agentapi.humanintheloopgate;

/**
 * The two channels this chapter adds: a held proposal ({@code apa.orders.pending-decisions}) and
 * the decision that resumes it ({@code apa.orders.decisions}), both keyed by the same correlation
 * id. Neither is one of the book's nine event-catalog topics in {@code domain-model}'s {@code
 * Topics} (they carry no order-lifecycle fact on their own, only the gate's own proposal/decision
 * protocol), so, the same way {@code AuditTopics} keeps chapter 21's topic local, they are kept
 * local to this example rather than added to the shared module.
 */
final class CancelGateTopics {

    static final String PENDING_DECISIONS = "apa.orders.pending-decisions";
    static final String DECISIONS = "apa.orders.decisions";

    private CancelGateTopics() {
    }
}
