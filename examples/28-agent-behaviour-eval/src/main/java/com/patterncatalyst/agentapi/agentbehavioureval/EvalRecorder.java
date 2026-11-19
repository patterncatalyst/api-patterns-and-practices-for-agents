package com.patterncatalyst.agentapi.agentbehavioureval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * The durable write a scored transcript ends at: one row per scored run, the run and order ids,
 * the full transcript (serialized JSON, an auditable record of exactly what was scored), the
 * deterministic verdict and, on FAIL, the one assertion that failed. Also increments a Micrometer
 * counter and logs a line, so a scored run is observable independent of which transcript produced
 * it, the same reasoning 26-decision-rule-review's {@code ReviewRecorder} states.
 */
@ApplicationScoped
public class EvalRecorder {

    private static final Logger LOG = Logger.getLogger(EvalRecorder.class);

    private final Counter evalsRecorded;
    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public EvalRecorder(MeterRegistry registry, DataSource dataSource) {
        this.evalsRecorded = Counter.builder("agentbehavioureval.eval.recorded")
                .description("Number of agent behaviour transcripts scored against the committed eval spec")
                .register(registry);
        this.dataSource = dataSource;
    }

    @Transactional
    public void record(Transcript transcript, EvalVerdict verdict) {
        evalsRecorded.increment();
        LOG.infof("eval recorded: runId=%s orderId=%s verdict=%s violatedAssertion=%s",
                transcript.runId(), transcript.orderId(), verdict.decision(), verdict.violatedAssertion());
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO eval.eval_runs (id, run_id, order_id, transcript, verdict, violated_assertion) "
                                + "VALUES (?, ?, ?, ?::jsonb, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, transcript.runId());
            insert.setString(3, transcript.orderId());
            insert.setString(4, mapper.writeValueAsString(transcript));
            insert.setString(5, verdict.decision().name());
            insert.setString(6, verdict.violatedAssertion());
            insert.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("eval record failed for " + transcript.runId(), e);
        }
    }
}
