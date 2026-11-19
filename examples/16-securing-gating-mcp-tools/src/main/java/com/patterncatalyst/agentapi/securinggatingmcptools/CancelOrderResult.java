package com.patterncatalyst.agentapi.securinggatingmcptools;

import java.math.BigDecimal;

/** The {@code cancelOrder} response: either the order was cancelled and refunded, or the user
 * declined the elicitation and {@code refundedAmount} is null because nothing was written. */
public record CancelOrderResult(String orderId, CancelOutcome outcome, BigDecimal refundedAmount) {

    public static CancelOrderResult cancelled(String orderId, BigDecimal refundedAmount) {
        return new CancelOrderResult(orderId, CancelOutcome.CANCELLED, refundedAmount);
    }

    public static CancelOrderResult rejectedByUser(String orderId) {
        return new CancelOrderResult(orderId, CancelOutcome.REJECTED_BY_USER, null);
    }
}
