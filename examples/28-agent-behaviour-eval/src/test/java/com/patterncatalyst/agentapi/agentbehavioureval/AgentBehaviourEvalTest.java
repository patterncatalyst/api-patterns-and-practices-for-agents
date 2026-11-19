package com.patterncatalyst.agentapi.agentbehavioureval;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole trigger-to-record path ({@link EvalRoute}, {@link EvalBean}, {@link
 * BehaviourEvaluator}, {@link EvalRecorder}) against Dev Services PostgreSQL, replaying the two
 * committed, recorded transcripts under {@code fixtures/}: {@code agent.eval.model-source} is
 * forced to {@code fixture} in the {@code %test} profile, so this test and {@code mvn -B verify}
 * need nothing model-shaped at all, no Ollama and no container. A unit-level mutation case then
 * confirms editing the good transcript's own recorded fields flips the verdict, proving the
 * evaluator's PASS is non-vacuous; {@code verify.sh} reproduces the same mutation live, against
 * the running container.
 */
@QuarkusTest
class AgentBehaviourEvalTest {

    @Inject
    DataSource dataSource;

    @Inject
    BehaviourEvaluator evaluator;

    private static String rawFixture(String name) throws IOException {
        try (InputStream in = AgentBehaviourEvalTest.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on the classpath: fixtures/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String verdictFor(String runId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            String verdict = columnFor("verdict", runId);
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

    private String columnFor(String column, String runId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT " + column + " FROM eval.eval_runs WHERE run_id = ? "
                                + "ORDER BY recorded_at DESC LIMIT 1")) {
            select.setString(1, runId);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read eval_runs for " + runId, e);
        }
    }

    @Test
    void theGoodRunIsRecordedPass() {
        given().when().get("/eval/run/good").then().statusCode(200);

        assertEquals("PASS", verdictFor("good-run", Duration.ofSeconds(10)));
        assertNull(columnFor("violated_assertion", "good-run"));
    }

    @Test
    void theRegressedRunIsRecordedFailNamingTheCallBudget() {
        given().when().get("/eval/run/regressed").then().statusCode(200);

        assertEquals("FAIL", verdictFor("regressed-run", Duration.ofSeconds(10)));
        assertEquals("call-budget-exceeded", columnFor("violated_assertion", "regressed-run"));
    }

    @Test
    void postingTheRawGoodTranscriptIsScoredAndRecordedTheSameWay() throws IOException {
        given().contentType("text/plain").body(rawFixture("good-run.jsonl"))
                .when().post("/eval")
                .then().statusCode(200);

        assertEquals("PASS", verdictFor("good-run", Duration.ofSeconds(10)));
    }

    @Test
    void theMutationFlipsTheVerdictFromPassToFail() {
        Transcript good = FixtureTranscripts.read("good-run.jsonl");
        EvalVerdict original = evaluator.evaluate(good);
        assertEquals(EvalVerdict.Decision.PASS, original.decision());

        Transcript refundNotCompleted = new Transcript(good.runId(), good.orderId(), good.toolCalls(),
                good.inputTokens(), good.outputTokens(), good.totalTokens(), good.finalOrderStatus(), false);
        EvalVerdict afterRefundMutation = evaluator.evaluate(refundNotCompleted);
        assertEquals(EvalVerdict.Decision.FAIL, afterRefundMutation.decision());
        assertEquals("refund-not-completed", afterRefundMutation.violatedAssertion());

        List<TranscriptToolCall> oneExtraCall = new ArrayList<>(good.toolCalls());
        oneExtraCall.add(new TranscriptToolCall("cancelAndRefund", Map.of(), false));
        Transcript tooManyCalls = new Transcript(good.runId(), good.orderId(), oneExtraCall,
                good.inputTokens(), good.outputTokens(), good.totalTokens(), good.finalOrderStatus(),
                good.refundCompleted());
        EvalVerdict afterCallMutation = evaluator.evaluate(tooManyCalls);
        assertEquals(EvalVerdict.Decision.FAIL, afterCallMutation.decision());
        assertEquals("call-budget-exceeded", afterCallMutation.violatedAssertion());
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
