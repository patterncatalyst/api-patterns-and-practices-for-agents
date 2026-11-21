package com.patterncatalyst.agentapi.afdeterministicdecisioning;

/**
 * The deterministic outcome of a rule firing: {@link Decision#APPROVE} by default, flipped to
 * {@link Decision#REJECT} with {@code failedClause} set by whichever committed DRL rule matches
 * first (salience orders the three clauses: the amount ceiling, then order status, then reason).
 * A plain mutable JavaBean, like {@link RefundProposal}: {@code refund-review.drl} inserts one of
 * these alongside the proposal and calls its setters from a rule's right-hand side, then
 * {@link RefundReviewer} reads the result back after {@code fireAllRules()}.
 */
public class Verdict {

    public enum Decision {
        APPROVE, REJECT
    }

    private Decision decision = Decision.APPROVE;
    private String failedClause;

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public String getFailedClause() {
        return failedClause;
    }

    public void setFailedClause(String failedClause) {
        this.failedClause = failedClause;
    }
}
