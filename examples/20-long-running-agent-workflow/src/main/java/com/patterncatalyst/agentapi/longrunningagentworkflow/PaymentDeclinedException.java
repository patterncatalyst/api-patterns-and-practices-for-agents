package com.patterncatalyst.agentapi.longrunningagentworkflow;

/**
 * Thrown by {@link FulfillmentService#takePayment(String)} when the demo decline rule refuses an
 * order's payment. Left uncaught, it propagates out of the {@code direct:takePayment} step, which
 * is exactly what tells the enclosing Camel Saga EIP (completion mode {@code AUTO}, the default)
 * to compensate every step enlisted so far, instead of completing.
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String orderId, String reason) {
        super("payment declined for " + orderId + ": " + reason);
    }
}
