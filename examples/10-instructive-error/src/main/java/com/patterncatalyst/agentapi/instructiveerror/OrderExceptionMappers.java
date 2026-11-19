package com.patterncatalyst.agentapi.instructiveerror;

import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Maps each domain exception to a distinct, actionable RFC 9457 problem+json body: a stable
 * {@code type}, a human {@code title}, the HTTP {@code status}, a {@code detail} specific to
 * this occurrence, and a remedy member that names what the caller can actually do about it.
 * Without these mappers, every domain exception falls through to the framework's bare default
 * error response: no {@code application/problem+json} content type, no remedy.
 */
public class OrderExceptionMappers {

    private static final String PROBLEM_JSON = "application/problem+json";

    @ServerExceptionMapper
    public RestResponse<NotFoundProblem> mapOrderNotFound(OrderNotFoundException e) {
        return RestResponse.ResponseBuilder.create(RestResponse.Status.NOT_FOUND, NotFoundProblem.of(e))
                .type(PROBLEM_JSON)
                .build();
    }

    @ServerExceptionMapper
    public RestResponse<ConflictProblem> mapOrderNotCancellable(OrderNotCancellableException e) {
        return RestResponse.ResponseBuilder.create(RestResponse.Status.CONFLICT, ConflictProblem.of(e))
                .type(PROBLEM_JSON)
                .build();
    }

    @ServerExceptionMapper
    public RestResponse<ValidationProblem> mapValidation(OrderValidationException e) {
        return RestResponse.ResponseBuilder.create(RestResponse.Status.BAD_REQUEST, ValidationProblem.of(e))
                .type(PROBLEM_JSON)
                .build();
    }
}
