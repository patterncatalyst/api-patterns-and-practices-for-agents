package com.patterncatalyst.agentapi.decisionrulereview;

import com.patterncatalyst.agentapi.OrderStatus;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The recorded agent run this example reviews: an agent's refund proposal ({@code refundAmount},
 * {@code reason}) plus the order facts ({@code orderStatus}, {@code orderPaidAmount})
 * {@link DecisionRuleReviewer} needs to judge it. Event/proposal payload records live per example,
 * never in {@code domain-model}: this one carries exactly what the committed rule's clauses read,
 * nothing from {@code Order} or {@code Payment}'s own lifecycle beyond those two facts. The
 * fixtures under {@code src/main/resources/fixtures/} and the body {@code POST /review} accepts
 * are both this shape, so a committed, recorded run and a live caller's proposal are reviewed
 * identically.
 */
public record RefundProposalRun(
        String orderId,
        String customerId,
        OrderStatus orderStatus,
        BigDecimal orderPaidAmount,
        BigDecimal refundAmount,
        String reason) {

    public RefundProposalRun {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(orderStatus, "orderStatus");
        Objects.requireNonNull(orderPaidAmount, "orderPaidAmount");
        Objects.requireNonNull(refundAmount, "refundAmount");
        Objects.requireNonNull(reason, "reason");
    }
}
