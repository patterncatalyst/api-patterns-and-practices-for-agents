package com.patterncatalyst.agentapi.afdeterministicdecisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.Map;

/**
 * The agent-facing surface: {@code reviewRefund} takes exactly what an agent would propose (an
 * order id, the stated reason, and the amount it wants refunded) plus the order's own facts, and
 * returns a verdict the agent cannot talk its way past. The tool never decides anything itself;
 * it hands the proposal to {@link RefundReviewer}, which fires the committed Drools ruleset, then
 * records the verdict through {@link ReviewRecorder} before returning it. "Model Reasons,
 * Application Controls": the model may propose any refund it likes, but this tool's rule engine,
 * not the model, decides whether it takes effect.
 */
@ApplicationScoped
public class RefundReviewTool {

    @Inject
    RefundReviewer reviewer;

    @Inject
    ReviewRecorder recorder;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "Review a proposed refund against the committed decision rule and record "
            + "the verdict. The rule, not the model, decides: the same proposal always reaches the "
            + "same APPROVE or REJECT verdict. On REJECT, the response names the one clause that "
            + "failed.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    public String reviewRefund(
            @ToolArg(description = "The order id, for example O-9001.") String orderId,
            @ToolArg(description = "The customer id the refund would go to.") String customerId,
            @ToolArg(description = "The order's current status: PLACED, PAID, SHIPPED, "
                    + "DELIVERED or CANCELLED.") String orderStatus,
            @ToolArg(description = "The amount actually paid on the order.") BigDecimal orderPaidAmount,
            @ToolArg(description = "The refund amount being proposed.") BigDecimal refundAmount,
            @ToolArg(description = "The stated reason for the refund, for example "
                    + "DAMAGED_IN_TRANSIT.") String reason) {
        RefundProposal proposal = new RefundProposal(
                orderId, customerId, orderStatus, orderPaidAmount, refundAmount, reason);

        Verdict verdict = reviewer.evaluate(proposal);
        recorder.record(proposal, verdict);

        return toJson(Map.of(
                "orderId", orderId,
                "decision", verdict.getDecision().name(),
                "failedClause", verdict.getFailedClause() == null ? "" : verdict.getFailedClause()));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize the verdict", e);
        }
    }
}
