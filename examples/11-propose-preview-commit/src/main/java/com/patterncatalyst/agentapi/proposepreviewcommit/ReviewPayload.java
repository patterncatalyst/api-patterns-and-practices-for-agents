package com.patterncatalyst.agentapi.proposepreviewcommit;

import java.math.BigDecimal;
import java.util.List;

/**
 * The structured answer to a {@code dryRun=true} call: what committing would do, computed by
 * reading current state, never by writing anything. {@code reversible} states the domain fact
 * that CANCELLED is a terminal {@code OrderStatus} (`_plans/domain-model.md`'s lifecycle rule):
 * there is no "uncancel" operation, so committing this proposal cannot be undone.
 */
public record ReviewPayload(
        String orderId,
        String effectSummary,
        String beforeStatus,
        String afterStatus,
        BigDecimal refundAmount,
        List<String> wouldEmitEvents,
        List<String> warnings,
        boolean reversible) {
}
