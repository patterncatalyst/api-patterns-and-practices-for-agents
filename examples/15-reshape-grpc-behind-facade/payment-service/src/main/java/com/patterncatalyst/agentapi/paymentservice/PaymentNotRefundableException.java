package com.patterncatalyst.agentapi.paymentservice;

import com.patterncatalyst.agentapi.PaymentStatus;

/** The payment for the given order exists but is not {@link PaymentStatus#COMPLETED}, so it
 * cannot move to {@link PaymentStatus#REFUNDED}. */
public class PaymentNotRefundableException extends RuntimeException {

    public PaymentNotRefundableException(String orderId, PaymentStatus status) {
        super("payment for order " + orderId + " is " + status + ", not COMPLETED, and cannot be refunded");
    }
}
