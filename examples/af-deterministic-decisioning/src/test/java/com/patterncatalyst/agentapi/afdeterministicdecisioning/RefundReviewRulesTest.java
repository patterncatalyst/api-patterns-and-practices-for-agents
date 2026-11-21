package com.patterncatalyst.agentapi.afdeterministicdecisioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;

/**
 * Exercises the committed DRL directly, with no CDI and no container: this is both the unit-level
 * proof of the refund-review decision and the follow-on to {@code DroolsSpikeTest}, now against
 * the real fact model and the real three-clause ruleset. Mirrors ch26's
 * {@code DecisionRuleReviewTest}: a compliant fixture reaches APPROVE, a violating fixture reaches
 * REJECT naming {@code amount-exceeds-paid}, each clause is independently provable, salience order
 * decides which clause is named when more than one fails, and the weakened ruleset (the
 * amount-ceiling rule removed) wrongly approves the exact same over-limit proposal the real
 * ruleset rejects.
 */
class RefundReviewRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final KieBase REAL_RULES = RefundReviewer.loadKieBase("rules/refund-review.drl");
    private static final KieBase WEAKENED_RULES =
            RefundReviewer.loadKieBase("rules/refund-review-weakened.drl");

    private static RefundProposal fixture(String name) throws IOException {
        try (InputStream in = RefundReviewRulesTest.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on the classpath: fixtures/" + name);
            }
            return MAPPER.readValue(in, RefundProposal.class);
        }
    }

    @Test
    void theCompliantFixtureIsApproved() throws IOException {
        Verdict verdict = RefundReviewer.evaluate(REAL_RULES, fixture("compliant-proposal.json"));

        assertEquals(Verdict.Decision.APPROVE, verdict.getDecision());
        assertNull(verdict.getFailedClause());
    }

    @Test
    void theViolatingFixtureIsRejectedWithTheAmountClause() throws IOException {
        Verdict verdict = RefundReviewer.evaluate(REAL_RULES, fixture("violating-proposal.json"));

        assertEquals(Verdict.Decision.REJECT, verdict.getDecision());
        assertEquals("amount-exceeds-paid", verdict.getFailedClause());
    }

    @Test
    void anUnrefundableOrderStatusIsRejectedWithItsOwnClause() {
        RefundProposal proposal = new RefundProposal(
                "O-AF9003", "CUST-AF9003", "CANCELLED",
                new BigDecimal("200.00"), new BigDecimal("50.00"), "DAMAGED_IN_TRANSIT");

        Verdict verdict = RefundReviewer.evaluate(REAL_RULES, proposal);

        assertEquals(Verdict.Decision.REJECT, verdict.getDecision());
        assertEquals("order-not-refundable", verdict.getFailedClause());
    }

    @Test
    void aDisallowedReasonIsRejectedWithItsOwnClause() {
        RefundProposal proposal = new RefundProposal(
                "O-AF9004", "CUST-AF9004", "PAID",
                new BigDecimal("200.00"), new BigDecimal("50.00"), "BUYERS_REMORSE");

        Verdict verdict = RefundReviewer.evaluate(REAL_RULES, proposal);

        assertEquals(Verdict.Decision.REJECT, verdict.getDecision());
        assertEquals("reason-not-allowed", verdict.getFailedClause());
    }

    @Test
    void whenMultipleClausesFailTheHighestSalienceClauseIsNamed() {
        // Fails all three clauses (over the paid amount, a terminal status, a disallowed reason);
        // the amount ceiling has the highest salience and must be the one named.
        RefundProposal proposal = new RefundProposal(
                "O-AF9005", "CUST-AF9005", "CANCELLED",
                new BigDecimal("200.00"), new BigDecimal("999.00"), "BUYERS_REMORSE");

        Verdict verdict = RefundReviewer.evaluate(REAL_RULES, proposal);

        assertEquals(Verdict.Decision.REJECT, verdict.getDecision());
        assertEquals("amount-exceeds-paid", verdict.getFailedClause());
    }

    @Test
    void theWeakenedRulesetApprovesTheSameViolatingProposal() throws IOException {
        Verdict verdict = RefundReviewer.evaluate(WEAKENED_RULES, fixture("violating-proposal.json"));

        assertEquals(Verdict.Decision.APPROVE, verdict.getDecision());
        assertNull(verdict.getFailedClause());
    }
}
