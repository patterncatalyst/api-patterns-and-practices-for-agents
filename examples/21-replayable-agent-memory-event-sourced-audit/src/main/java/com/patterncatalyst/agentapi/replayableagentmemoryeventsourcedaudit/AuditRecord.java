package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One append-only entry of the agent action log, published unchanged to
 * {@link AuditTopics#AGENT_ACTIONS} and folded, never mutated, into the projection. {@code id}
 * is the record's own identity (the projection's idempotency key, so a redelivered record folds
 * exactly once); {@code tokensIn}, {@code tokensOut} and {@code costUsd} are populated only for
 * {@link AuditDirection#OUTBOUND} (a model call) and stay {@code null} for
 * {@link AuditDirection#INBOUND} (a tool call the agent consumed). {@code detail} is a short,
 * deterministic summary of the decision or call, never the model's own prose: OTel GenAI content
 * capture (the full prompt or completion) stays off, the same hold recorded in
 * {@code _plans/versions.md}.
 */
public record AuditRecord(
        String id,
        AuditDirection direction,
        String orderId,
        String actor,
        String action,
        String detail,
        Integer tokensIn,
        Integer tokensOut,
        BigDecimal costUsd,
        Instant occurredAt) {

    public AuditRecord {
        text(id, "id");
        Objects.requireNonNull(direction, "direction");
        text(orderId, "orderId");
        text(actor, "actor");
        text(action, "action");
        text(detail, "detail");
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
