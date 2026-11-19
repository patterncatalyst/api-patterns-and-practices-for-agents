package com.patterncatalyst.agentapi.mcpfacade;

import com.patterncatalyst.agentapi.payment.v1.Payment;
import com.patterncatalyst.agentapi.payment.v1.PaymentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one reshape this example exists to show: an agent sends a JSON decimal amount, for example
 * {@code "49.99"}; {@link #toCents} turns it into the {@code int64} cents the payment-service's
 * {@code PaymentService} expects on the wire, and {@link #toView} turns a returned
 * {@link Payment}'s cents and {@link PaymentStatus} enum back into lean, decimal, instructive JSON
 * through {@link PaymentView}. Nothing here passes the proto message through unreshaped.
 */
final class AmountReshape {

    private AmountReshape() {
    }

    static long toCents(String decimalAmount) {
        BigDecimal amount = new BigDecimal(decimalAmount).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be a positive decimal, got " + decimalAmount);
        }
        return amount.movePointRight(2).longValueExact();
    }

    static String toDecimal(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    static PaymentView toView(Payment payment) {
        return new PaymentView(payment.getOrderId(), toDecimal(payment.getAmountCents()),
                statusText(payment.getStatus()));
    }

    static String statusText(PaymentStatus status) {
        return switch (status) {
            case PENDING -> "PENDING";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case REFUNDED -> "REFUNDED";
            case PAYMENT_STATUS_UNSPECIFIED, UNRECOGNIZED -> "UNKNOWN";
        };
    }
}
