package com.patterncatalyst.agentapi.proposepreviewcommit;

/**
 * The body of {@code POST /orders/{id}/cancel}. {@code dryRun} is the propose/preview flag on
 * this same intent endpoint: there is no separate {@code /preview} endpoint. {@code decision}
 * carries the caller's own verdict after reviewing a preview; the only recognized value today
 * is {@code "REJECTED_BY_USER"}, a graceful no-op, never an error.
 */
public record CancelRequest(String reason, Boolean dryRun, String decision) {

    public boolean isDryRun() {
        return Boolean.TRUE.equals(dryRun);
    }

    public boolean isRejectedByUser() {
        return "REJECTED_BY_USER".equals(decision);
    }
}
