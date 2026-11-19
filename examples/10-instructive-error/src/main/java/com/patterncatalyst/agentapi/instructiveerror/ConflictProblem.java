package com.patterncatalyst.agentapi.instructiveerror;

import java.util.List;

/**
 * RFC 9457 problem: the order exists but cannot be cancelled from its current state.
 * {@code currentState} and {@code allowedTransitions} are the actionable remedy members: what
 * state the order is actually in, and what it can legally move to instead, not a bare "conflict".
 */
public record ConflictProblem(
        String type, String title, int status, String detail, String currentState, List<String> allowedTransitions) {

    static ConflictProblem of(OrderNotCancellableException e) {
        return new ConflictProblem(
                "https://errors.patterncatalyst.dev/problems/order-not-cancellable",
                "Order not cancellable",
                409,
                e.getMessage(),
                e.currentState().name(),
                e.allowedTransitions().stream().map(Enum::name).toList());
    }
}
