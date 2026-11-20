package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
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
 * rejected with a {@code 429} {@code application/problem+json} body and must grow both the
 * durable {@code rate_limit} row count and the {@code ingest.rejected} Micrometer counter.
 */
@QuarkusTest
@TestProfile(RateLimitBurstTest.SmallRateLimitProfile.class)
class RateLimitBurstTest {

    @Inject
    RejectionRecorder rejectionRecorder;

    @Inject
    MeterRegistry registry;

    private static final String VALID_PAYLOAD = """
            {"orderId":"O-9201","customerId":"C-4821","itemSku":"SKU-7734","quantity":1,"amount":9.99}
            """;

    @Test
    void theCallBeyondTheLimitIsRejectedWithAProblemJsonAndADurableCounter() {
        for (int i = 0; i < 3; i++) {
            given().contentType("application/json").body(VALID_PAYLOAD)
                    .when().post("/ingest/orders")
                    .then().statusCode(202);
        }

        long rowsBefore = rejectionRecorder.count("rate_limit");
        double counterBefore = counterValue();

        given().contentType("application/json").body(VALID_PAYLOAD)
                .when().post("/ingest/orders")
                .then().statusCode(429)
                .contentType("application/problem+json")
                .body("type", org.hamcrest.Matchers.equalTo(
                        "https://errors.patterncatalyst.dev/problems/ingest-rate-limited"))
                .body("status", org.hamcrest.Matchers.equalTo(429));

        assertTrue(rejectionRecorder.count("rate_limit") > rowsBefore,
                "the 4th call must leave a new trafficguardrails.rejections row");
        assertTrue(counterValue() > counterBefore,
                "the 4th call must grow the ingest.rejected{reason=rate_limit} counter");
    }

    private double counterValue() {
        var counter = registry.find("ingest.rejected").tag("reason", "rate_limit").counter();
        return counter == null ? 0.0 : counter.count();
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
