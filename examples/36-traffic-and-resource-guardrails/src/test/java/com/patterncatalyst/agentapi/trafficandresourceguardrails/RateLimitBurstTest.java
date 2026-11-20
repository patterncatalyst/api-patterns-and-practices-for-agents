package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises guardrail 1 alone, in its own test profile (its own, freshly booted application
 * instance), so the rate-limit state singleton {@code IngestResource.ingestOrder} keeps across
 * test methods starts at zero: fires 3 valid calls, all accepted, then a 4th that must be
 * rejected with a {@code 429} {@code application/problem+json} body and must grow the durable
 * {@code rate_limit} row count. The matching {@code ingest.rejected} Micrometer counter is
 * asserted against Prometheus in the live {@code verify.sh} run, where the metric is observed
 * after a scrape rather than read in-process the instant the response commits.
 */
@QuarkusTest
@TestProfile(RateLimitBurstTest.SmallRateLimitProfile.class)
class RateLimitBurstTest {

    @Inject
    RejectionRecorder rejectionRecorder;

    private static final String VALID_PAYLOAD = """
            {"orderId":"O-9201","customerId":"C-4821","itemSku":"SKU-7734","quantity":1,"amount":9.99}
            """;

    @Test
    void theCallBeyondTheLimitIsRejectedWithAProblemJsonAndADurableRow() {
        for (int i = 0; i < 3; i++) {
            given().contentType("application/json").body(VALID_PAYLOAD)
                    .when().post("/ingest/orders")
                    .then().statusCode(202);
        }

        long rowsBefore = rejectionRecorder.count("rate_limit");

        given().contentType("application/json").body(VALID_PAYLOAD)
                .when().post("/ingest/orders")
                .then().statusCode(429)
                .contentType("application/problem+json")
                .body("type", org.hamcrest.Matchers.equalTo(
                        "https://errors.patterncatalyst.dev/problems/ingest-rate-limited"))
                .body("status", org.hamcrest.Matchers.equalTo(429));

        // The exception mapper records the row after the 429 response has committed, so poll
        // briefly rather than reading it the instant the call returns.
        awaitTrue(() -> rejectionRecorder.count("rate_limit") > rowsBefore,
                "the 4th call must leave a new trafficguardrails.rejections row");
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    /** value=3, window=30s: small enough to reach the limit in one test method, isolated from every other test class. */
    public static class SmallRateLimitProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.fault-tolerance.global.rate-limit.value", "3",
                    "quarkus.fault-tolerance.global.rate-limit.window", "30");
        }
    }
}
