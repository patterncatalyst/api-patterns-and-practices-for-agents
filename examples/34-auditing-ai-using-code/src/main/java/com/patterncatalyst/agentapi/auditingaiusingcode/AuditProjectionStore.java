package com.patterncatalyst.agentapi.auditingaiusingcode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * The projection side of the append-only outbound-call log: {@code audit.outbound_call_log} is
 * the live read model, kept current by {@link AuditProjectionRoute} folding
 * {@link AuditTopics#OUTBOUND_CALLS} as it arrives; {@code audit.outbound_call_replay} is a
 * scratch table {@link ReplayService} rebuilds from scratch on every {@code /audit/replay} call,
 * by reading the same log from its beginning. Every insert is keyed on the record's own
 * {@code id} with {@code ON CONFLICT DO NOTHING}, so folding the same committed record twice (a
 * Kafka redelivery, or replaying the whole log) changes nothing: the fold is idempotent, which is
 * what makes it safe to call from scratch, repeatedly, without ever producing a different answer
 * for the same log.
 */
@ApplicationScoped
public class AuditProjectionStore {

    /** Demo-only in-place correction: {@link #mutateArgumentDigest} bypasses the log entirely. */
    enum ProjectionTable {
        LIVE("audit.outbound_call_log"),
        REPLAY("audit.outbound_call_replay");

        final String table;

        ProjectionTable(String table) {
            this.table = table;
        }
    }

    private static final Logger LOG = Logger.getLogger(AuditProjectionStore.class);

    @Inject
    DataSource dataSource;

    /** Called by the Camel route folding the live log: the one legitimate write path for LIVE. */
    public void appendLive(OutboundCallAuditRecord record) {
        append(ProjectionTable.LIVE, record);
        LOG.infof("outbound call audit record folded into the live projection: id=%s correlationId=%s target=%s",
                record.id(), record.correlationId(), record.target());
    }

    /** Called only by {@link ReplayService}, rebuilding REPLAY (or, for recovery, LIVE) from the log. */
    void append(ProjectionTable table, OutboundCallAuditRecord record) {
        String sql = switch (table) {
            case LIVE -> """
                    INSERT INTO audit.outbound_call_log
                        (id, correlation_id, direction, target, argument_digest, occurred_at, tokens_in, tokens_out, cost_usd)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """;
            case REPLAY -> """
                    INSERT INTO audit.outbound_call_replay
                        (id, correlation_id, direction, target, argument_digest, occurred_at, tokens_in, tokens_out, cost_usd)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """;
        };
        try (Connection c = dataSource.getConnection(); PreparedStatement insert = c.prepareStatement(sql)) {
            insert.setString(1, record.id());
            insert.setString(2, record.correlationId());
            insert.setString(3, record.direction());
            insert.setString(4, record.target());
            insert.setString(5, record.argumentDigest());
            insert.setTimestamp(6, Timestamp.from(record.occurredAt()));
            setNullableInt(insert, 7, record.tokensIn());
            setNullableInt(insert, 8, record.tokensOut());
            insert.setBigDecimal(9, record.costUsd());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not fold outbound call audit record " + record.id() + " into " + table, e);
        }
    }

    /** Demo-only, gated by {@code audit.mutable} in {@link AuditResource}: mutates a committed row in place,
     * never appending a correcting record to the log. Returns whether a row existed to mutate. */
    boolean mutateArgumentDigest(String id, String newArgumentDigest) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement update = c.prepareStatement(
                        "UPDATE audit.outbound_call_log SET argument_digest = ? WHERE id = ?")) {
            update.setString(1, newArgumentDigest);
            update.setString(2, id);
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not mutate outbound call audit record " + id, e);
        }
    }

    /** Empties REPLAY (or, for recovery, LIVE) so the caller can rebuild it from scratch. */
    void truncate(ProjectionTable table) {
        try (Connection c = dataSource.getConnection(); var stmt = c.createStatement()) {
            stmt.executeUpdate("TRUNCATE TABLE " + table.table);
        } catch (SQLException e) {
            throw new IllegalStateException("could not truncate " + table, e);
        }
    }

    /** Every row for one correlation id, in the order it was appended. */
    List<OutboundCallAuditRecord> rowsFor(String correlationId) {
        String sql = """
                SELECT id, correlation_id, direction, target, argument_digest, occurred_at, tokens_in, tokens_out, cost_usd
                FROM audit.outbound_call_log WHERE correlation_id = ? ORDER BY occurred_at, id
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement select = c.prepareStatement(sql)) {
            select.setString(1, correlationId);
            try (ResultSet rs = select.executeQuery()) {
                List<OutboundCallAuditRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(toRecord(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read outbound call audit log for " + correlationId, e);
        }
    }

    /** A deterministic, order-independent summary of every row in a table: equal strings mean equal tables. */
    String snapshot(ProjectionTable table) {
        String sql = switch (table) {
            case LIVE -> """
                    SELECT coalesce(string_agg(
                        id || '|' || correlation_id || '|' || direction || '|' || target || '|' || argument_digest
                            || '|' || coalesce(tokens_in::text, '-') || '|' || coalesce(tokens_out::text, '-')
                            || '|' || coalesce(cost_usd::text, '-') || '|' || extract(epoch from occurred_at)::text,
                        E'\\n' ORDER BY id), '') FROM audit.outbound_call_log
                    """;
            case REPLAY -> """
                    SELECT coalesce(string_agg(
                        id || '|' || correlation_id || '|' || direction || '|' || target || '|' || argument_digest
                            || '|' || coalesce(tokens_in::text, '-') || '|' || coalesce(tokens_out::text, '-')
                            || '|' || coalesce(cost_usd::text, '-') || '|' || extract(epoch from occurred_at)::text,
                        E'\\n' ORDER BY id), '') FROM audit.outbound_call_replay
                    """;
        };
        try (Connection c = dataSource.getConnection(); var stmt = c.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not snapshot " + table, e);
        }
    }

    long count(ProjectionTable table) {
        String sql = switch (table) {
            case LIVE -> "SELECT count(*) FROM audit.outbound_call_log";
            case REPLAY -> "SELECT count(*) FROM audit.outbound_call_replay";
        };
        try (Connection c = dataSource.getConnection(); var stmt = c.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not count " + table, e);
        }
    }

    /** Count of rows for one correlation id in LIVE, used to prove a disabled interceptor appends nothing new. */
    long countFor(String correlationId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT count(*) FROM audit.outbound_call_log WHERE correlation_id = ?")) {
            select.setString(1, correlationId);
            try (ResultSet rs = select.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count outbound call audit rows for " + correlationId, e);
        }
    }

    private static void setNullableInt(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, java.sql.Types.INTEGER);
        } else {
            stmt.setInt(index, value);
        }
    }

    private static OutboundCallAuditRecord toRecord(ResultSet rs) throws SQLException {
        Integer tokensIn = (Integer) rs.getObject("tokens_in");
        Integer tokensOut = (Integer) rs.getObject("tokens_out");
        BigDecimal costUsd = rs.getBigDecimal("cost_usd");
        Instant occurredAt = rs.getTimestamp("occurred_at").toInstant();
        return new OutboundCallAuditRecord(rs.getString("id"), rs.getString("correlation_id"),
                rs.getString("direction"), rs.getString("target"), rs.getString("argument_digest"), occurredAt,
                tokensIn, tokensOut, costUsd);
    }
}
