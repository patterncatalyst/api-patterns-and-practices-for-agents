package com.patterncatalyst.agentapi.paymentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.patterncatalyst.agentapi.payment.v1.PaymentResponse;
import com.patterncatalyst.agentapi.payment.v1.PaymentService;
import com.patterncatalyst.agentapi.payment.v1.PaymentStatus;
import com.patterncatalyst.agentapi.payment.v1.RefundRequest;
import com.patterncatalyst.agentapi.payment.v1.TakePaymentRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Drives {@code PaymentService} in-process: a {@code @GrpcClient} against the same Quarkus test
 * instance that hosts {@link PaymentGrpcService}, needing no separate container or host
 * configuration (the pattern the gRPC reference guide uses to test an exposed service without
 * TLS).
 */
@QuarkusTest
class PaymentGrpcServiceTest {

    @GrpcClient
    PaymentService client;

    @Test
    void takePaymentCreatesACompletedPaymentFromDecimalCents() {
        PaymentResponse response = client.takePayment(TakePaymentRequest.newBuilder()
                .setOrderId("O-7001")
                .setAmountCents(4999)
                .build())
                .await().indefinitely();

        assertEquals("O-7001", response.getPayment().getOrderId());
        assertEquals(4999, response.getPayment().getAmountCents());
        assertEquals(PaymentStatus.COMPLETED, response.getPayment().getStatus());
    }

    @Test
    void refundPaymentMovesTheSeededCompletedPaymentToRefunded() {
        PaymentResponse refunded = client.refundPayment(RefundRequest.newBuilder()
                .setOrderId("O-9001")
                .setAmountCents(4999)
                .build())
                .await().indefinitely();

        assertEquals("O-9001", refunded.getPayment().getOrderId());
        assertEquals(PaymentStatus.REFUNDED, refunded.getPayment().getStatus());
    }

    @Test
    void refundPaymentForAnUnknownOrderFailsWithNotFound() {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> client.refundPayment(
                RefundRequest.newBuilder().setOrderId("O-0000").setAmountCents(100).build())
                .await().indefinitely());

        assertEquals(Status.Code.NOT_FOUND, failure.getStatus().getCode());
    }

    @Test
    void takePaymentWithANonPositiveAmountFailsWithInvalidArgument() {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> client.takePayment(
                TakePaymentRequest.newBuilder().setOrderId("O-7002").setAmountCents(0).build())
                .await().indefinitely());

        assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    }

    @Test
    void refundingAnAlreadyRefundedPaymentFailsWithFailedPrecondition() {
        // Its own order id (not the O-9001 seed), so this test is independent of execution order
        // against refundPaymentMovesTheSeededCompletedPaymentToRefunded.
        client.takePayment(TakePaymentRequest.newBuilder().setOrderId("O-7004").setAmountCents(2500).build())
                .await().indefinitely();
        client.refundPayment(RefundRequest.newBuilder().setOrderId("O-7004").setAmountCents(2500).build())
                .await().indefinitely();

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> client.refundPayment(
                RefundRequest.newBuilder().setOrderId("O-7004").setAmountCents(2500).build())
                .await().indefinitely());

        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }
}
