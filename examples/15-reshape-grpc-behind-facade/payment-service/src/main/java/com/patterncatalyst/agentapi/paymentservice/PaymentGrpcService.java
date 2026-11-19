package com.patterncatalyst.agentapi.paymentservice;

import com.patterncatalyst.agentapi.payment.v1.PaymentResponse;
import com.patterncatalyst.agentapi.payment.v1.PaymentService;
import com.patterncatalyst.agentapi.payment.v1.RefundRequest;
import com.patterncatalyst.agentapi.payment.v1.TakePaymentRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;

/**
 * The gRPC server face of the separate payment-service image: {@code TakePayment} and
 * {@code RefundPayment} over the domain {@link com.patterncatalyst.agentapi.Payment}, backed by
 * Postgres through {@link PaymentRepository}. JDBC is blocking, so both methods carry
 * {@code @Blocking}; without it they would run on the Vert.x event loop and could stall every
 * other gRPC call this service handles. Failures become {@link StatusRuntimeException}s with a
 * status code and description an instructive-error mapper on the other side of the wire (the
 * mcp-facade) can turn into agent-readable text, never a raw stack trace.
 */
@GrpcService
public class PaymentGrpcService implements PaymentService {

    @Inject
    PaymentRepository repository;

    @Override
    @Blocking
    public Uni<PaymentResponse> takePayment(TakePaymentRequest request) {
        if (request.getOrderId().isBlank()) {
            return Uni.createFrom().failure(invalidArgument("order_id is required"));
        }
        if (request.getAmountCents() <= 0) {
            return Uni.createFrom().failure(invalidArgument("amount_cents must be positive"));
        }
        return Uni.createFrom().item(() -> PaymentGrpcMapper.toResponse(
                repository.takePayment(request.getOrderId(), PaymentGrpcMapper.toAmount(request.getAmountCents()))));
    }

    @Override
    @Blocking
    public Uni<PaymentResponse> refundPayment(RefundRequest request) {
        if (request.getOrderId().isBlank()) {
            return Uni.createFrom().failure(invalidArgument("order_id is required"));
        }
        return Uni.createFrom().item(() -> PaymentGrpcMapper.toResponse(repository.refundPayment(request.getOrderId())))
                .onFailure(PaymentNotFoundException.class)
                .transform(e -> new StatusRuntimeException(Status.NOT_FOUND.withDescription(e.getMessage())))
                .onFailure(PaymentNotRefundableException.class)
                .transform(e -> new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(e.getMessage())));
    }

    private static StatusRuntimeException invalidArgument(String description) {
        return new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(description));
    }
}
