package com.patterncatalyst.agentapi.agentbehavioureval;

import java.util.List;

/**
 * The committed eval spec this chapter is about ({@code contracts/28-behaviour-eval.json}): the
 * tool names the task is allowed to call, the call and token budgets, and the durable outcome a
 * run must reach, loaded once at startup by {@link BehaviourEvaluator}. Never a weakened variant:
 * the negative witness here is two different transcripts scored against this SAME spec (see
 * README), not a weakened spec swapped in like 26-decision-rule-review's rule file.
 */
public record EvalSpec(
        String evalId,
        int version,
        String description,
        List<String> expectedToolSequence,
        int maxToolCalls,
        int maxArgErrors,
        int maxTotalTokens,
        boolean requireRefundCompleted,
        String requireFinalOrderStatus) {
}
