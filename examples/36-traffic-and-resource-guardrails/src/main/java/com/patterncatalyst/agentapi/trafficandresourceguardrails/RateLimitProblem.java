package com.patterncatalyst.agentapi.trafficandresourceguardrails;

/**
 * RFC 9457 problem for the rate-limit guardrail: the caller exceeded the configured number of
 * ingest calls for the current window. {@code remedy} is the actionable member: what the caller
 * can do about it, not a bare 429.
 */
public record RateLimitProblem(String type, String title, int status, String detail, String remedy) {

    public static RateLimitProblem of() {
        return new RateLimitProblem(
                "https://errors.patterncatalyst.dev/problems/ingest-rate-limited",
                "Too many requests",
                429,
                "the ingest endpoint rejected this call: the caller's request rate exceeded the "
                        + "configured limit for the current window",
                "wait for the current rate-limit window to elapse, then retry; a caller that needs "
                        + "sustained high volume should negotiate a dedicated quota rather than retry "
                        + "into the same limit");
    }
}
