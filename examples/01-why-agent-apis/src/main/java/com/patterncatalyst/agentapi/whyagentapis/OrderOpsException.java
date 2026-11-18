package com.patterncatalyst.agentapi.whyagentapis;

/**
 * A business rule violation raised by {@link OrderOpsService}. Every failure carries both a
 * short opaque code (what the bad contract's granular tools surface, with no remedy) and a
 * full instructive message (what the good contract's single intent tool surfaces instead).
 */
public class OrderOpsException extends RuntimeException {

    public enum Code {
        NOT_FOUND("E404"),
        INVALID_TRANSITION("E409"),
        NOT_CANCELLED("E409"),
        AMOUNT_MISMATCH("E400"),
        ALREADY_REFUNDED("E409"),
        MISSING_FIELD("E400");

        private final String opaque;

        Code(String opaque) {
            this.opaque = opaque;
        }

        /** The bare code the bad contract's tools return, with no remedy text. */
        public String opaque() {
            return opaque;
        }
    }

    private final Code code;

    public OrderOpsException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
