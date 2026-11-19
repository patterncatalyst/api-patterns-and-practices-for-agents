package com.patterncatalyst.agentapi.mcpfacade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.payment.v1.PaymentResponse;
import com.patterncatalyst.agentapi.payment.v1.PaymentService;
import com.patterncatalyst.agentapi.payment.v1.RefundRequest;
import com.patterncatalyst.agentapi.payment.v1.TakePaymentRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Supplier;

/**
 * Reshape not wrap: the one heterogeneous-protocol pattern in this book. The payment-service
 * behind this facade speaks gRPC, not REST or MCP; {@code takePayment} and {@code refundPayment}
 * reshape a JSON decimal amount into the {@code int64} cents {@code payment.proto} requires,
 * call the separate payment-service image over a real gRPC channel through {@link #paymentService},
 * and reshape its {@code PaymentResponse} back into lean, decimal, instructive JSON. An agent
 * calling this facade never sees a proto message, a cents integer or a gRPC status code.
 */
@ApplicationScoped
public class PaymentMcpTools {

    @GrpcClient("payment-service")
    PaymentService paymentService;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "Take a payment for an order. amount is a decimal string, for example "
            + "\"49.99\". Returns the order id, the decimal amount taken and the resulting "
            + "payment status.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    @WrapBusinessError(PaymentFacadeException.class)
    public String takePayment(@ToolArg(description = "The order id, for example O-9001.") String orderId,
            @ToolArg(description = "The amount to take, as a decimal string, for example 49.99.") String amount) {
        TakePaymentRequest request = TakePaymentRequest.newBuilder()
                .setOrderId(orderId)
                .setAmountCents(AmountReshape.toCents(amount))
                .build();
        return callAndReshape(() -> paymentService.takePayment(request).await().indefinitely());
    }

    @Tool(description = "Refund a completed payment for an order. amount is a decimal string "
            + "matching the amount originally taken, for example \"49.99\". Returns the order "
            + "id, the decimal amount and the resulting payment status.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    @WrapBusinessError(PaymentFacadeException.class)
    public String refundPayment(@ToolArg(description = "The order id, for example O-9001.") String orderId,
            @ToolArg(description = "The amount to refund, as a decimal string, for example 49.99.") String amount) {
        RefundRequest request = RefundRequest.newBuilder()
                .setOrderId(orderId)
                .setAmountCents(AmountReshape.toCents(amount))
                .build();
        return callAndReshape(() -> paymentService.refundPayment(request).await().indefinitely());
    }

    private String callAndReshape(Supplier<PaymentResponse> grpcCall) {
        PaymentResponse response;
        try {
            response = grpcCall.get();
        } catch (StatusRuntimeException grpcError) {
            throw new PaymentFacadeException(instructiveMessage(grpcError));
        }
        try {
            return objectMapper.writeValueAsString(AmountReshape.toView(response.getPayment()));
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize the payment result", e);
        }
    }

    /** Turns a gRPC status into the kind of message an agent can act on, never the bare code or a
     * stack trace: this is the instructive-error half of the reshape, mirroring ch10's pattern
     * across a protocol boundary instead of a REST one. */
    static String instructiveMessage(StatusRuntimeException grpcError) {
        Status.Code code = grpcError.getStatus().getCode();
        String description = grpcError.getStatus().getDescription();
        return switch (code) {
            case NOT_FOUND -> "No payment found for that order: " + description;
            case FAILED_PRECONDITION -> "That payment cannot be refunded: " + description;
            case INVALID_ARGUMENT -> "The request was rejected: " + description;
            default -> "The payment service call failed (" + code + "): " + description;
        };
    }
}
