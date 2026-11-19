package com.patterncatalyst.agentapi.instructiveerror;

/**
 * RFC 9457 problem: a field in the request body failed a validation rule. {@code field} is the
 * actionable remedy member: which field to fix, not a bare "bad request".
 */
public record ValidationProblem(String type, String title, int status, String detail, String field) {

    static ValidationProblem of(OrderValidationException e) {
        return new ValidationProblem(
                "https://errors.patterncatalyst.dev/problems/order-validation",
                "Order request is invalid",
                400,
                e.getMessage(),
                e.field());
    }
}
