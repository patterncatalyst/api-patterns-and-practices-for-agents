package com.patterncatalyst.agentapi.mcpfacade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.payment.v1.Payment;
import com.patterncatalyst.agentapi.payment.v1.PaymentResponse;
import com.patterncatalyst.agentapi.payment.v1.PaymentService;
import com.patterncatalyst.agentapi.payment.v1.PaymentStatus;
import com.patterncatalyst.agentapi.payment.v1.RefundRequest;
import com.patterncatalyst.agentapi.payment.v1.TakePaymentRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The reshape through the tool surface, with the separate payment-service image mocked out: these
 * tests exercise only {@link PaymentMcpTools}' own logic (the amount and error reshape), not a
 * live gRPC connection, so they do not require the payment-service container to be running.
 */
@QuarkusTest
class PaymentMcpToolsTest {

    @InjectMock
    @GrpcClient("payment-service")
    PaymentService paymentService;

    @Inject
    PaymentMcpTools tools;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void takePaymentReshapesTheDecimalAmountIntoCentsOnTheWayInAndBackOnTheWayOut() throws Exception {
        ArgumentCaptor<TakePaymentRequest> captor = ArgumentCaptor.forClass(TakePaymentRequest.class);
        when(paymentService.takePayment(any())).thenReturn(Uni.createFrom().item(PaymentResponse.newBuilder()
                .setPayment(Payment.newBuilder()
                        .setId("PAY-9001")
                        .setOrderId("O-9001")
                        .setAmountCents(4999)
                        .setStatus(PaymentStatus.COMPLETED)
                        .build())
                .build()));

        String json = tools.takePayment("O-9001", "49.99");

        org.mockito.Mockito.verify(paymentService).takePayment(captor.capture());
        assertEquals(4999, captor.getValue().getAmountCents(), "the decimal amount must reshape to cents on the way in");

        JsonNode body = MAPPER.readTree(json);
        assertEquals("O-9001", body.path("orderId").asText());
        assertEquals("49.99", body.path("amount").asText(), "cents must reshape back to a decimal string");
        assertEquals("COMPLETED", body.path("status").asText());
    }

    @Test
    void refundPaymentSurfacesAGrpcNotFoundAsAnInstructiveMessageNotARawStatusCode() {
        when(paymentService.refundPayment(any())).thenReturn(
                Uni.createFrom().failure(new StatusRuntimeException(
                        Status.NOT_FOUND.withDescription("no payment for order O-0000"))));

        PaymentFacadeException failure = assertThrows(PaymentFacadeException.class,
                () -> tools.refundPayment("O-0000", "10.00"));

        assertTrue(failure.getMessage().contains("No payment found"), "the message must be instructive, not a bare code");
        assertTrue(failure.getMessage().contains("O-0000"));
    }

    @Test
    void refundPaymentSurfacesAGrpcFailedPreconditionAsAnInstructiveMessage() {
        when(paymentService.refundPayment(any())).thenReturn(
                Uni.createFrom().failure(new StatusRuntimeException(
                        Status.FAILED_PRECONDITION.withDescription("payment for order O-9002 is REFUNDED"))));

        PaymentFacadeException failure = assertThrows(PaymentFacadeException.class,
                () -> tools.refundPayment("O-9002", "10.00"));

        assertTrue(failure.getMessage().contains("cannot be refunded"));
    }

    @Test
    void refundRequestCarriesTheReshapedCentsToo() {
        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        when(paymentService.refundPayment(any())).thenReturn(Uni.createFrom().item(PaymentResponse.newBuilder()
                .setPayment(Payment.newBuilder()
                        .setId("PAY-9001")
                        .setOrderId("O-9001")
                        .setAmountCents(4999)
                        .setStatus(PaymentStatus.REFUNDED)
                        .build())
                .build()));

        tools.refundPayment("O-9001", "49.99");

        org.mockito.Mockito.verify(paymentService).refundPayment(captor.capture());
        assertEquals(4999, captor.getValue().getAmountCents());
    }
}
