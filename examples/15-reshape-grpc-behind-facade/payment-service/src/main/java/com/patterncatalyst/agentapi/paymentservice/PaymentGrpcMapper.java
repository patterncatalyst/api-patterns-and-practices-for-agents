package com.patterncatalyst.agentapi.paymentservice;

import com.patterncatalyst.agentapi.Payment;
import com.patterncatalyst.agentapi.PaymentStatus;
import com.patterncatalyst.agentapi.payment.v1.PaymentResponse;
import java.math.BigDecimal;

/**
 * Maps between the shared domain {@link Payment}/{@link PaymentStatus} (examples/domain-model)
 * and the wire-level proto messages generated from {@code payment.proto}. Proto3 has no decimal
 * type, so every amount crosses the gRPC boundary as {@code int64} cents; this is the one place
 * in this service where that conversion happens.
 */
final class PaymentGrpcMapper {

    private PaymentGrpcMapper() {
    }

    static PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.newBuilder()
                .setPayment(com.patterncatalyst.agentapi.payment.v1.Payment.newBuilder()
                        .setId(payment.id())
                        .setOrderId(payment.orderId())
                        .setAmountCents(toCents(payment.amount()))
                        .setStatus(toProtoStatus(payment.status()))
                        .build())
                .build();
    }

    static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    static BigDecimal toAmount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    static com.patterncatalyst.agentapi.payment.v1.PaymentStatus toProtoStatus(PaymentStatus status) {
        return switch (status) {
            case PENDING -> com.patterncatalyst.agentapi.payment.v1.PaymentStatus.PENDING;
            case COMPLETED -> com.patterncatalyst.agentapi.payment.v1.PaymentStatus.COMPLETED;
            case FAILED -> com.patterncatalyst.agentapi.payment.v1.PaymentStatus.FAILED;
            case REFUNDED -> com.patterncatalyst.agentapi.payment.v1.PaymentStatus.REFUNDED;
        };
    }
}
