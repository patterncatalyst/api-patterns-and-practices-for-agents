package com.patterncatalyst.agentapi.decisionrulereview;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The deterministic gate this chapter is about: the same committed decision rule applied to the
 * same proposal always reaches the same verdict, independent of the model that proposed the
 * refund. "Model Reasons, Application Controls" made concrete: an agent may propose any refund it
 * likes, but this reviewer, not the model, decides.
 *
 * <p>{@code review.rule.path} (demo code, like ch24's {@code MCP_APA_ORDERS_URL} and ch25's
 * {@code CONSUMER_ASYNC_ENABLED}) names the rule file read once at startup. Pointed at the
 * weakened variant (the same rule with its amount-ceiling clause removed), the exact same
 * violating proposal is approved instead of rejected, proving that clause is exactly what caught
 * it.
 *
 * <p>No model, no randomness, no rule engine: clauses are evaluated by a plain switch on
 * {@code type}, in the order they are written in the committed JSON, and the first one a proposal
 * fails is the recorded verdict's {@code failedClause}. This is a hand-rolled JSON-rule evaluator,
 * deliberately not Drools or DMN; Appendix F builds the same refund-review decision again as a
 * Drools/DMN ruleset.
 */
@ApplicationScoped
public class DecisionRuleReviewer {

    private static final Logger LOG = Logger.getLogger(DecisionRuleReviewer.class);

    @ConfigProperty(name = "review.rule.path")
    String rulePath;

    private DecisionRule rule;

    @PostConstruct
    void loadRule() {
        try {
            String json = Files.readString(Path.of(rulePath));
            rule = new ObjectMapper().readValue(json, DecisionRule.class);
            LOG.infof("decision rule loaded: ruleId=%s path=%s clauses=%d",
                    rule.ruleId(), rulePath, rule.clauses().size());
        } catch (IOException e) {
            throw new IllegalStateException("could not read decision rule file " + rulePath, e);
        }
    }

    public Verdict evaluate(RefundProposalRun run) {
        return evaluate(rule, run);
    }

    /**
     * Package-visible so the unit-level weakened-rule test can evaluate a proposal against an
     * arbitrary {@link DecisionRule} without going through CDI or a second configured bean.
     */
    static Verdict evaluate(DecisionRule rule, RefundProposalRun run) {
        for (RuleClause clause : rule.clauses()) {
            if (!holds(clause, run)) {
                return Verdict.reject(clause.id());
            }
        }
        return Verdict.approve();
    }

    private static boolean holds(RuleClause clause, RefundProposalRun run) {
        return switch (clause.type()) {
            case "amountNotGreaterThanPaid" -> run.refundAmount().compareTo(run.orderPaidAmount()) <= 0;
            case "orderStatusIn" -> clause.allowed().contains(run.orderStatus().name());
            case "reasonIn" -> clause.allowed().contains(run.reason());
            default -> throw new IllegalStateException("unknown clause type: " + clause.type());
        };
    }
}
