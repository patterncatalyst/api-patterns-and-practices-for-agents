package com.patterncatalyst.agentapi.decisionrulereview;

import java.util.List;

/**
 * One clause of a committed {@link DecisionRule}, read as data, never compiled or interpreted by
 * a rule engine: {@link DecisionRuleReviewer} evaluates each clause's {@code type} with a plain
 * switch. {@code allowed} is used by the {@code orderStatusIn} and {@code reasonIn} clause types
 * only; {@code amountNotGreaterThanPaid} ignores it and compares the proposal's own two amount
 * fields instead.
 */
public record RuleClause(String id, String type, List<String> allowed, String description) {
}
