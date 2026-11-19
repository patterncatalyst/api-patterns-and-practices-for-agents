package com.patterncatalyst.agentapi.decisionrulereview;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole trigger-to-record path ({@link ReviewRoute}, {@link ReviewBean},
 * {@link DecisionRuleReviewer}, {@link ReviewRecorder}) against Dev Services PostgreSQL, replaying
 * the two committed, recorded agent runs under {@code fixtures/}: no model is ever on this path,
 * since the reviewer this chapter is about is deterministic by construction, so {@code mvn -B
 * verify} and CI need nothing model-shaped at all. A third, unit-level case confirms the weakened
 * rule (the amount-ceiling clause removed) approves the exact same violating proposal the real
 * rule rejects, proving that clause is exactly what catches it; {@code verify.sh} reproduces this
 * same witness live, with the app container itself pointed at the weakened rule file.
 */
@QuarkusTest
class DecisionRuleReviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DataSource dataSource;

    private static RefundProposalRun fixture(String name) throws IOException {
        try (InputStream in = DecisionRuleReviewTest.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on the classpath: fixtures/" + name);
            }
            return MAPPER.readValue(in, RefundProposalRun.class);
        }
    }

    private String verdictFor(String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            String verdict = columnFor("verdict", orderId);
            if (verdict != null) {
                return verdict;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private String columnFor(String column, String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT " + column + " FROM review.reviews WHERE order_id = ? "
                                + "ORDER BY reviewed_at DESC LIMIT 1")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read reviews for " + orderId, e);
        }
    }

    @Test
    void theCompliantRecordedRunIsApproved() throws IOException {
        RefundProposalRun run = fixture("compliant-run.json");

        given().contentType("application/json").body(MAPPER.writeValueAsString(run))
                .when().post("/review")
                .then().statusCode(200);

        assertEquals("APPROVE", verdictFor(run.orderId(), Duration.ofSeconds(10)));
        assertNull(columnFor("failed_clause", run.orderId()));
    }

    @Test
    void theViolatingRecordedRunIsRejectedWithTheAmountClause() throws IOException {
        RefundProposalRun run = fixture("violating-run.json");

        given().contentType("application/json").body(MAPPER.writeValueAsString(run))
                .when().post("/review")
                .then().statusCode(200);

        assertEquals("REJECT", verdictFor(run.orderId(), Duration.ofSeconds(10)));
        assertEquals("amount-exceeds-paid", columnFor("failed_clause", run.orderId()));
    }

    @Test
    void theWeakenedRuleApprovesTheSameViolatingProposal() throws IOException {
        RefundProposalRun run = fixture("violating-run.json");
        DecisionRule weakened = MAPPER.readValue(
                Files.readString(Path.of("../../contracts/26-refund-review-rule.weakened.json")),
                DecisionRule.class);

        Verdict verdict = DecisionRuleReviewer.evaluate(weakened, run);

        assertEquals(Verdict.Decision.APPROVE, verdict.decision());
        assertNull(verdict.failedClause());
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
