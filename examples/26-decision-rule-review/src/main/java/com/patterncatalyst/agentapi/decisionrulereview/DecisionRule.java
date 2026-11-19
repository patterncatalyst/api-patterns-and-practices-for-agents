package com.patterncatalyst.agentapi.decisionrulereview;

import java.util.List;

/**
 * The decision rule as data: the committed JSON this chapter is about
 * ({@code contracts/26-refund-review-rule.json}), or its weakened variant used only by
 * {@code verify.sh}'s negative witness, loaded once at startup by {@link DecisionRuleReviewer}.
 * Deliberately not Drools or DMN: a hand-rolled, ordered list of clauses, each evaluated by a
 * plain switch on {@code type}. Appendix F builds the same refund-review decision again as a
 * Drools/DMN ruleset; the lesson here is the same either way, that committing the rule as data
 * makes the verdict reproducible independent of the model that proposed the action.
 */
public record DecisionRule(String ruleId, int version, String description, List<RuleClause> clauses) {
}
