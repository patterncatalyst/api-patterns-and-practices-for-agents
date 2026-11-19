package com.patterncatalyst.agentapi.mcpfacade;

/** An instructive, tool-facing message derived from a {@link io.grpc.StatusRuntimeException}: an
 * agent caller never sees a gRPC status code or a stack trace. */
public class PaymentFacadeException extends RuntimeException {

    public PaymentFacadeException(String message) {
        super(message);
    }
}
