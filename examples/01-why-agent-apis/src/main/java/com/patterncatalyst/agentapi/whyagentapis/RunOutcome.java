package com.patterncatalyst.agentapi.whyagentapis;

import java.util.List;

/**
 * One measured run of the cancel-and-refund task against one contract. This is the shape both
 * the LIVE run's JSON response and the committed replay fixtures share (see README).
 */
public record RunOutcome(
        String contract,
        String task,
        int toolCalls,
        int argErrors,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        String finalOrderStatus,
        boolean refundCompleted,
        List<String> toolTrace,
        String modelReply) {
}
