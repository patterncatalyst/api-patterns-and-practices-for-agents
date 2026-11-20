package com.patterncatalyst.agentapi.identityandauthorizationguardrails;

import java.math.BigDecimal;

/** The {@code cancelOrder} response. Unlike a confirm-gated cancel, a step-up-gated cancel has no
 * decline branch: a caller presenting a token that clears the write scope and the step-up acr
 * floor has already supplied the stronger credential a destructive write requires, so reaching
 * this record always means the order was cancelled and refunded. */
public record CancelOrderResult(String orderId, String outcome, BigDecimal refundedAmount) {

    public static CancelOrderResult cancelled(String orderId, BigDecimal refundedAmount) {
        return new CancelOrderResult(orderId, "CANCELLED", refundedAmount);
    }
}
