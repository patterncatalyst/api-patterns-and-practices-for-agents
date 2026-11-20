package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import java.util.List;

/**
 * RFC 9457 problem for the schema-validity guardrail: the payload does not conform to the
 * registered ingest schema. {@code violations} is the actionable member: the specific fields that
 * failed, not a bare 400.
 */
public record ValidationProblem(String type, String title, int status, String detail, List<String> violations) {

    public static ValidationProblem of(List<String> violations) {
        return new ValidationProblem(
                "https://errors.patterncatalyst.dev/problems/ingest-schema-invalid",
                "Payload failed schema validation",
                400,
                "the ingest payload does not conform to the registered ingest schema",
                violations);
    }
}
