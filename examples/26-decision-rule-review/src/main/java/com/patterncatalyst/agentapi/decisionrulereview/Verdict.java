package com.patterncatalyst.agentapi.decisionrulereview;

/**
 * The deterministic outcome of a review: {@link Decision#APPROVE} or {@link Decision#REJECT},
 * with {@code failedClause} naming the one clause that failed (the {@code id} from the committed
 * rule), null on {@code APPROVE}. The verdict is a pure function of the committed rule and the
 * proposal's own fields, never of a model: the same proposal reviewed against the same rule always
 * reaches the same verdict.
 */
public record Verdict(Decision decision, String failedClause) {

    public enum Decision { APPROVE, REJECT }

    public static Verdict approve() {
        return new Verdict(Decision.APPROVE, null);
    }

    public static Verdict reject(String failedClause) {
        return new Verdict(Decision.REJECT, failedClause);
    }
}
