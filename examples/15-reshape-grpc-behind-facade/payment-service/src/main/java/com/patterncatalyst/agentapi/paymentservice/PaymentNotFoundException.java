package com.patterncatalyst.agentapi.paymentservice;

/** No payment exists for the given order id. */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String orderId) {
        super("no payment for order " + orderId);
    }
}
