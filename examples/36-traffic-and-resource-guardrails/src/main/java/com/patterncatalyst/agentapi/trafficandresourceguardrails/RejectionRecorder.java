package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * The durable effect both guardrails leave behind: one {@code trafficguardrails.rejections} row
 * per rejected call, tagged by reason, plus a Micrometer counter of the same shape, so a bite can
 * assert a captured side effect that outlives the HTTP response, not only a transient status code.
 * {@code reason} is either {@code "rate_limit"} or {@code "schema_invalid"}.
 */
@ApplicationScoped
public class RejectionRecorder {

    private static final Logger LOG = Logger.getLogger(RejectionRecorder.class);

    private final DataSource dataSource;
    private final MeterRegistry registry;

    @Inject
    public RejectionRecorder(DataSource dataSource, MeterRegistry registry) {
        this.dataSource = dataSource;
        this.registry = registry;
    }

    public void record(String reason) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert =
                        c.prepareStatement("INSERT INTO trafficguardrails.rejections (reason) VALUES (?)")) {
            insert.setString(1, reason);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("rejection record failed for reason " + reason, e);
        }
        Counter.builder("ingest.rejected")
                .description("Number of ingest calls rejected by a traffic or resource guardrail")
                .tag("reason", reason)
                .register(registry)
                .increment();
        LOG.infof("ingest rejected: reason=%s", reason);
    }

    public long count(String reason) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT count(*) FROM trafficguardrails.rejections WHERE reason = ?")) {
            select.setString(1, reason);
            var rs = select.executeQuery();
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("rejection count failed for reason " + reason, e);
        }
    }
}
