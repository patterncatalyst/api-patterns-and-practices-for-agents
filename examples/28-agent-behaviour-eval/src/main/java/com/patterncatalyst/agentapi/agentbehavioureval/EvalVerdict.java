package com.patterncatalyst.agentapi.agentbehavioureval;

/**
 * The deterministic outcome of scoring one transcript: {@link Decision#PASS} or {@link
 * Decision#FAIL}, with {@code violatedAssertion} naming the one assertion that failed, null on
 * {@code PASS}. A pure function of the committed eval spec and the transcript's own captured
 * fields, never of model prose: the same transcript scored against the same spec always reaches
 * the same verdict.
 */
public record EvalVerdict(Decision decision, String violatedAssertion) {

    public enum Decision { PASS, FAIL }

    public static EvalVerdict pass() {
        return new EvalVerdict(Decision.PASS, null);
    }

    public static EvalVerdict fail(String violatedAssertion) {
        return new EvalVerdict(Decision.FAIL, violatedAssertion);
    }
}
