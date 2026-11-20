package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Exercises guardrail 2 alone: a valid payload is accepted, an invalid one is rejected with a
 * {@code 400} {@code application/problem+json} body naming its violations and leaves a durable
 * {@code schema_invalid} row behind. Stays well under the rate limit's 5-per-30-second window (two
 * calls), so {@link RateLimitBurstTest} owns the burst assertion in its own, isolated test profile.
 */
@QuarkusTest
class IngestResourceTest {

    @Inject
    RejectionRecorder rejectionRecorder;

    @Test
    void validPayloadIsAccepted() {
        given().contentType("application/json")
                .body("""
                        {"orderId":"O-9101","customerId":"C-4821","itemSku":"SKU-7734","quantity":2,"amount":19.98}
                        """)
                .when().post("/ingest/orders")
                .then().statusCode(202)
                .body("orderId", equalTo("O-9101"))
                .body("status", equalTo("ACCEPTED"));
    }

    @Test
    void invalidPayloadIsRejectedWithProblemJsonAndADurableRow() {
        long before = rejectionRecorder.count("schema_invalid");

        Response response = given().contentType("application/json")
                .body("""
                        {"orderId":"O-9102","itemSku":"SKU-7734","quantity":0,"amount":19.98}
                        """)
                .when().post("/ingest/orders");

        response.then().statusCode(400).contentType("application/problem+json");
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://errors.patterncatalyst.dev/problems/ingest-schema-invalid", response.jsonPath().getString("type"));
        org.junit.jupiter.api.Assertions.assertEquals(400, response.jsonPath().getInt("status"));
        org.hamcrest.MatcherAssert.assertThat(response.jsonPath().getList("violations").size(), greaterThan(0));

        long after = rejectionRecorder.count("schema_invalid");
        assertTrue(after > before, "a rejected payload must leave a new trafficguardrails.rejections row");
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
