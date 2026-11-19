package com.patterncatalyst.agentapi.decisionrulereview;

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
 * The durable write a review ends at: one row per reviewed proposal, the order id, the full
 * proposal (serialized JSON, an auditable record of exactly what was reviewed), the deterministic
 * verdict and, on REJECT, the clause that failed. Also increments a Micrometer counter and logs a
 * line, so the review is observable independent of which rule file produced it, the same
 * reasoning {@code NotificationRecorder} states in 25-consuming-the-async-side.
 */
@ApplicationScoped
public class ReviewRecorder {

    private static final Logger LOG = Logger.getLogger(ReviewRecorder.class);

    private final Counter reviews;
    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public ReviewRecorder(MeterRegistry registry, DataSource dataSource) {
        this.reviews = Counter.builder("decisionrulereview.review.recorded")
                .description("Number of refund proposals reviewed against the committed decision rule")
                .register(registry);
        this.dataSource = dataSource;
    }

    @Transactional
    public void record(RefundProposalRun run, Verdict verdict) {
        reviews.increment();
        LOG.infof("review recorded: orderId=%s verdict=%s failedClause=%s",
                run.orderId(), verdict.decision(), verdict.failedClause());
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO review.reviews (id, order_id, proposal, verdict, failed_clause) "
                                + "VALUES (?, ?, ?::jsonb, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, run.orderId());
            insert.setString(3, mapper.writeValueAsString(run));
            insert.setString(4, verdict.decision().name());
            insert.setString(5, verdict.failedClause());
            insert.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("review record failed for " + run.orderId(), e);
        }
    }
}
