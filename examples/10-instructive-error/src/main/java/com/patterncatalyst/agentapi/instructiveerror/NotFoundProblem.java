package com.patterncatalyst.agentapi.instructiveerror;

/**
 * RFC 9457 problem: no order exists with the given id. {@code nextStep} is the actionable
 * remedy member: what the caller can do about it, not a bare 404.
 */
public record NotFoundProblem(String type, String title, int status, String detail, String nextStep) {

    static NotFoundProblem of(OrderNotFoundException e) {
        return new NotFoundProblem(
                "https://errors.patterncatalyst.dev/problems/order-not-found",
                "Order not found",
                404,
                e.getMessage(),
                "place a new order with POST /orders, then retry with the orderId that call returns");
    }
}
