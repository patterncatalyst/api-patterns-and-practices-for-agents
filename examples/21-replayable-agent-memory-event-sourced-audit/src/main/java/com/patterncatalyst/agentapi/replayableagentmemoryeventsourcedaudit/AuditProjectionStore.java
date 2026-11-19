package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

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
 * The projection side of the append-only log: {@code shipping.audit_log_projection} is the
 * live read model, kept current by {@link AuditProjectionRoute} folding
 * {@link AuditTopics#AGENT_ACTIONS} as it arrives; {@code shipping.audit_replay} is a scratch
 * table {@link ReplayService} rebuilds from scratch on every {@code /audit/replay} call, by
 * reading the same log from its beginning. Both tables share one shape; which one a method
 * touches is picked by a fixed {@link ProjectionTable} constant, never a table name built from a
 * variable, so there is no SQL injection surface even though the values here are never
 * caller-controlled. Every insert is keyed on the record's own {@code id} with
 * {@code ON CONFLICT DO NOTHING}, so folding the same committed record twice (a Kafka
 * redelivery, or replaying the whole log) changes nothing: the fold is idempotent, which is what
 * makes it safe to call from scratch, repeatedly, without ever producing a different answer for
 * the same log.
 */
@ApplicationScoped
public class AuditProjectionStore {

    /** Demo-only in-place correction: {@link #mutateDetail} bypasses the log entirely. */
    enum ProjectionTable {
        LIVE("shipping.audit_log_projection"),
        REPLAY("shipping.audit_replay");

        final String table;

        ProjectionTable(String table) {
            this.table = table;
        }
    }

    private static final Logger LOG = Logger.getLogger(AuditProjectionStore.class);

    @Inject
    DataSource dataSource;

    /** Called by the Camel route folding the live log: the one legitimate write path for LIVE. */
    public void appendLive(AuditRecord record) {
        append(ProjectionTable.LIVE, record);
        LOG.infof("audit record folded into the live projection: id=%s direction=%s orderId=%s", record.id(),
                record.direction(), record.orderId());
    }

    /** Called only by {@link ReplayService}, rebuilding REPLAY (or, for recovery, LIVE) from the log. */
    void append(ProjectionTable table, AuditRecord record) {
        String sql = switch (table) {
            case LIVE -> """
                    INSERT INTO shipping.audit_log_projection
                        (id, direction, order_id, actor, action, detail, tokens_in, tokens_out, cost_usd, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """;
            case REPLAY -> """
                    INSERT INTO shipping.audit_replay
                        (id, direction, order_id, actor, action, detail, tokens_in, tokens_out, cost_usd, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """;
        };
        try (Connection c = dataSource.getConnection(); PreparedStatement insert = c.prepareStatement(sql)) {
            insert.setString(1, record.id());
            insert.setString(2, record.direction().name());
            insert.setString(3, record.orderId());
            insert.setString(4, record.actor());
            insert.setString(5, record.action());
            insert.setString(6, record.detail());
            setNullableInt(insert, 7, record.tokensIn());
            setNullableInt(insert, 8, record.tokensOut());
            insert.setBigDecimal(9, record.costUsd());
            insert.setTimestamp(10, Timestamp.from(record.occurredAt()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not fold audit record " + record.id() + " into " + table, e);
        }
    }

    /** Demo-only, gated by {@code audit.mutable} in {@link AuditResource}: mutates a committed row in place,
     * never appending a correcting record. Returns whether a row existed to mutate. */
    boolean mutateDetail(String id, String newDetail) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement update = c.prepareStatement(
                        "UPDATE shipping.audit_log_projection SET detail = ? WHERE id = ?")) {
            update.setString(1, newDetail);
            update.setString(2, id);
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not mutate audit record " + id, e);
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

    /** Every row for one order, in the order it was appended: both directions interleaved by time. */
    List<AuditRecord> rowsFor(String orderId) {
        String sql = """
                SELECT id, direction, order_id, actor, action, detail, tokens_in, tokens_out, cost_usd, occurred_at
                FROM shipping.audit_log_projection WHERE order_id = ? ORDER BY occurred_at, id
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement select = c.prepareStatement(sql)) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                List<AuditRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(toRecord(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read audit log for " + orderId, e);
        }
    }

    /** A deterministic, order-independent summary of every row in a table: equal strings mean equal tables. */
    String snapshot(ProjectionTable table) {
        String sql = switch (table) {
            case LIVE -> """
                    SELECT coalesce(string_agg(
                        id || '|' || direction || '|' || order_id || '|' || actor || '|' || action || '|' || detail
                            || '|' || coalesce(tokens_in::text, '-') || '|' || coalesce(tokens_out::text, '-')
                            || '|' || coalesce(cost_usd::text, '-') || '|' || extract(epoch from occurred_at)::text,
                        E'\\n' ORDER BY id), '') FROM shipping.audit_log_projection
                    """;
            case REPLAY -> """
                    SELECT coalesce(string_agg(
                        id || '|' || direction || '|' || order_id || '|' || actor || '|' || action || '|' || detail
                            || '|' || coalesce(tokens_in::text, '-') || '|' || coalesce(tokens_out::text, '-')
                            || '|' || coalesce(cost_usd::text, '-') || '|' || extract(epoch from occurred_at)::text,
                        E'\\n' ORDER BY id), '') FROM shipping.audit_replay
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
            case LIVE -> "SELECT count(*) FROM shipping.audit_log_projection";
            case REPLAY -> "SELECT count(*) FROM shipping.audit_replay";
        };
        try (Connection c = dataSource.getConnection(); var stmt = c.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not count " + table, e);
        }
    }

    private static void setNullableInt(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, java.sql.Types.INTEGER);
        } else {
            stmt.setInt(index, value);
        }
    }

    private static AuditRecord toRecord(ResultSet rs) throws SQLException {
        Integer tokensIn = (Integer) rs.getObject("tokens_in");
        Integer tokensOut = (Integer) rs.getObject("tokens_out");
        BigDecimal costUsd = rs.getBigDecimal("cost_usd");
        Instant occurredAt = rs.getTimestamp("occurred_at").toInstant();
        return new AuditRecord(rs.getString("id"), AuditDirection.valueOf(rs.getString("direction")),
                rs.getString("order_id"), rs.getString("actor"), rs.getString("action"), rs.getString("detail"),
                tokensIn, tokensOut, costUsd, occurredAt);
    }
}
