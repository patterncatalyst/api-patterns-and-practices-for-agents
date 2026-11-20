package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import io.smallrye.faulttolerance.api.RateLimitException;
import jakarta.inject.Inject;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Maps each guardrail's exception to a distinct, actionable RFC 9457 {@code application/problem+json}
 * body, and records the durable rejection (a {@code trafficguardrails.rejections} row, a tagged
 * Micrometer counter, a log line) the moment the exception actually fires, so both guardrails
 * leave the same kind of evidence behind. Defined outside any REST resource class (the same
 * global-mapper placement ch10's {@code OrderExceptionMappers} uses), since
 * {@code RateLimitException} is thrown by the {@code @RateLimit} interceptor wrapping
 * {@link IngestResource#ingestOrder}, not by a line inside that method body.
 */
public class GuardrailExceptionMappers {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    RejectionRecorder rejectionRecorder;

    @ServerExceptionMapper
    public RestResponse<RateLimitProblem> mapRateLimited(RateLimitException e) {
        rejectionRecorder.record("rate_limit");
        return RestResponse.ResponseBuilder.create(RestResponse.Status.TOO_MANY_REQUESTS, RateLimitProblem.of())
                .type(PROBLEM_JSON)
                .build();
    }

    @ServerExceptionMapper
    public RestResponse<ValidationProblem> mapSchemaViolation(SchemaViolationException e) {
        rejectionRecorder.record("schema_invalid");
        return RestResponse.ResponseBuilder
                .create(RestResponse.Status.BAD_REQUEST, ValidationProblem.of(e.violations()))
                .type(PROBLEM_JSON)
                .build();
    }
}
