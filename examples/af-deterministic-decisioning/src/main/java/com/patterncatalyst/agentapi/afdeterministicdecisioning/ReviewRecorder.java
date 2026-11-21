package com.patterncatalyst.agentapi.afdeterministicdecisioning;

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
 * The durable write a review ends at: one row per reviewed proposal, the order id, the proposed
 * amount and reason, the deterministic verdict and, on REJECT, the clause that failed. Also
 * increments a Micrometer counter and logs a line, the same observability shape ch26's
 * {@code ReviewRecorder} uses, so a review done by the Drools ruleset is observable without
 * re-reading the rule engine's own trace.
 */
@ApplicationScoped
public class ReviewRecorder {

    private static final Logger LOG = Logger.getLogger(ReviewRecorder.class);

    private final Counter reviews;
    private final DataSource dataSource;

    @Inject
    public ReviewRecorder(MeterRegistry registry, DataSource dataSource) {
        this.reviews = Counter.builder("afdeterministicdecisioning.review.recorded")
                .description("Number of refund proposals reviewed against the committed Drools ruleset")
                .register(registry);
        this.dataSource = dataSource;
    }

    @Transactional
    public void record(RefundProposal proposal, Verdict verdict) {
        reviews.increment();
        LOG.infof("review recorded: orderId=%s verdict=%s failedClause=%s",
                proposal.getOrderId(), verdict.getDecision(), verdict.getFailedClause());
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO refunds.reviews "
                                + "(id, order_id, customer_id, refund_amount, reason, verdict, failed_clause) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, proposal.getOrderId());
            insert.setString(3, proposal.getCustomerId());
            insert.setBigDecimal(4, proposal.getRefundAmount());
            insert.setString(5, proposal.getReason());
            insert.setString(6, verdict.getDecision().name());
            insert.setString(7, verdict.getFailedClause());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("review record failed for " + proposal.getOrderId(), e);
        }
    }
}
