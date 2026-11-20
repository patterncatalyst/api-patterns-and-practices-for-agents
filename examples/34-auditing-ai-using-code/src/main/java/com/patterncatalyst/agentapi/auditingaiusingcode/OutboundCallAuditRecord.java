package com.patterncatalyst.agentapi.auditingaiusingcode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One append-only entry of the outbound-call audit log: a record of a call this code's own
 * langchain4j agent made OUT to another API, never a call made in to this service (that is the
 * inward half 21-replayable-agent-memory-event-sourced-audit already covers, and the model-call
 * telemetry a sibling observability chapter covers). {@code id} is the record's own identity,
 * the projection's idempotency key, so a redelivered record folds exactly once.
 * {@code correlationId} ties the call back to the business operation that triggered it (here, the
 * order id). {@code target} names what was called (a tool name, or an HTTP target for a call that
 * is not MCP at all). {@code argumentDigest} is a SHA-256 hex digest of the call's arguments,
 * never the raw arguments themselves, so the log can prove a call happened and with what inputs
 * without becoming a second place sensitive argument values are stored. {@code tokensIn},
 * {@code tokensOut} and {@code costUsd} are populated only when the outbound call itself is a
 * model call with a token cost; they stay {@code null} for a tool call with no token cost of its
 * own.
 */
public record OutboundCallAuditRecord(
        String id,
        String correlationId,
        String direction,
        String target,
        String argumentDigest,
        Instant occurredAt,
        Integer tokensIn,
        Integer tokensOut,
        BigDecimal costUsd) {

    /** The only direction this log ever records: a call this code's own agent made out. */
    public static final String OUTBOUND = "OUTBOUND";

    public OutboundCallAuditRecord {
        text(id, "id");
        text(correlationId, "correlationId");
        text(direction, "direction");
        if (!OUTBOUND.equals(direction)) {
            throw new IllegalArgumentException(
                    "direction must be " + OUTBOUND + ": this log audits only calls this code makes out");
        }
        text(target, "target");
        text(argumentDigest, "argumentDigest");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (tokensIn != null && tokensIn < 0) {
            throw new IllegalArgumentException("tokensIn must not be negative");
        }
        if (tokensOut != null && tokensOut < 0) {
            throw new IllegalArgumentException("tokensOut must not be negative");
        }
        if (costUsd != null) {
            costUsd = costUsd.setScale(6, java.math.RoundingMode.HALF_UP);
        }
    }

    private static void text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
