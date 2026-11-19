package com.patterncatalyst.agentapi.instructiveerror;

/**
 * A field in the request body failed a validation rule. Carries which field, so
 * {@link OrderExceptionMappers} can name the offending field, not just say "bad request".
 */
public class OrderValidationException extends RuntimeException {

    private final String field;

    public OrderValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
