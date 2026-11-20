package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole trigger-to-recorder path (REST resource, {@link AgentTrafficBean},
 * {@link FixtureTokenUsageAdvisor}, {@link TokenCostRecorder}) against Dev Services PostgreSQL.
 * MODEL=off (the {@code %test} profile forces {@code agent.traffic.model-source=fixture}): these
 * tests never call Ollama, so {@code mvn -B verify} and CI stay model-free. The live, real-model
 * round trip through {@link OrderStatusAdvisor} and its GenAI span and token-usage metric is
 * asserted by {@code verify.sh} instead, since a model's own wording and tool-calling are
 * nondeterministic.
 */
@QuarkusTest
class ObservabilityForAgentTrafficTest {

    @Inject
    DataSource dataSource;

    @Inject
    TokenCostRecorder recorder;

    private record Row(String model, int inputTokens, int outputTokens, BigDecimal costUsd) {
    }

    private Row tokenCostRowFor(String model, int inputTokens, int outputTokens) {
        // callId is a fresh UUID per call, so the row is identified by the fixture's own token
        // counts and model instead.
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT model, input_tokens, output_tokens, cost_usd FROM agenttraffic.token_cost "
                                + "WHERE model = ? AND input_tokens = ? AND output_tokens = ? "
                                + "ORDER BY recorded_at DESC LIMIT 1")) {
            select.setString(1, model);
            select.setInt(2, inputTokens);
            select.setInt(3, outputTokens);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    return new Row(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getBigDecimal(4));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read agenttraffic.token_cost", e);
        }
        return null;
    }

    @Test
    void anOrderNamedInTheFixtureIsRecordedWithItsRecordedTokenCountAndTheDerivedCost() {
        // O-3301 is recorded in fixtures/token-usage.jsonl as inputTokens=128, outputTokens=42.
        given().get("/agent/orders/O-3301/status").then().statusCode(200);

        Row row = tokenCostRowFor("qwen3:4b-instruct-2507-q4_K_M", 128, 42);
        assertNotNull(row, "a token_cost row for the fixture's own token counts");
        assertEquals(0, recorder.cost(128, 42).compareTo(row.costUsd()),
                "the persisted cost must equal what TokenCostRecorder.cost computes from the same counts");
    }

    @Test
    void anOrderNotInTheFixtureFallsBackToTheFixedTokenCount() {
        given().get("/agent/orders/O-NEVER-SEEDED/status").then().statusCode(200);

        Row row = tokenCostRowFor("qwen3-fixture", 64, 16);
        assertNotNull(row, "a token_cost row for the fixed fallback token count");
        assertEquals(0, recorder.cost(64, 16).compareTo(row.costUsd()));
    }

    @Test
    void theRecorderComputesACostThatGrowsWithEitherTokenCount() {
        BigDecimal baseline = recorder.cost(100, 100);
        assertTrue(recorder.cost(200, 100).compareTo(baseline) > 0, "more input tokens cost more");
        assertTrue(recorder.cost(100, 200).compareTo(baseline) > 0, "more output tokens cost more");
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
